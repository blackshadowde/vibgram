package org.telegram.messenger.vibgram.bot

import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_keyboard

/**
 * Turns engine MessageObjects into immutable [BotMessage]s.
 * Call on the UI thread (MessageObjects are mutated there); the result is safe to share anywhere.
 */
internal object BotMessageParser {

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "3gp", "ts", "flv", "wmv", "mpg", "mpeg")

    fun parse(messageObject: MessageObject): BotMessage {
        val owner = messageObject.messageOwner
        val markup = owner?.reply_markup
        return BotMessage(
            id = messageObject.id,
            chatId = messageObject.dialogId,
            date = owner?.date ?: 0,
            editDate = owner?.edit_date ?: 0,
            isOutgoing = messageObject.isOutOwner,
            text = owner?.message.orEmpty(),
            inlineKeyboard = (markup as? TLRPC.TL_replyInlineMarkup)?.let(::parseInlineKeyboard) ?: emptyList(),
            replyKeyboard = (markup as? TLRPC.TL_replyKeyboardMarkup)?.let(::parseReplyKeyboard),
            hidesReplyKeyboard = markup is TLRPC.TL_replyKeyboardHide,
            video = parseVideo(messageObject),
            isSending = messageObject.isSending,
            sendFailed = messageObject.isSendError,
        ).withSource(messageObject)
    }

    // ---- keyboards -----------------------------------------------------------------------------

    private fun parseInlineKeyboard(markup: TLRPC.TL_replyInlineMarkup): List<List<BotButton>> =
        markup.rows.orEmpty().mapIndexedNotNull { r, row ->
            row?.buttons.orEmpty().mapIndexedNotNull { c, button -> button?.let { parseInlineButton(it, r, c) } }
                .takeIf { it.isNotEmpty() }
        }

    private fun parseInlineButton(button: TL_keyboard.KeyboardInlineButton, r: Int, c: Int): BotButton {
        val text = button.text.orEmpty()
        return when (val type = button.type) {
            is TL_keyboard.TL_inlineButtonTypeCallback ->
                BotButton.Callback(text, r, c, type.data ?: ByteArray(0), type.requires_password)
            is TL_keyboard.TL_inlineButtonTypeUrl -> BotButton.Url(text, r, c, type.url.orEmpty())
            is TL_keyboard.TL_inlineButtonTypeUrlAuth -> BotButton.LoginUrl(text, r, c, type.url.orEmpty())
            is TL_keyboard.TL_inlineButtonTypeWebView -> BotButton.WebApp(text, r, c, type.url.orEmpty())
            is TL_keyboard.TL_inlineButtonTypeSwitchInline -> BotButton.SwitchInline(text, r, c, type.query.orEmpty(), type.same_peer)
            is TL_keyboard.TL_inlineButtonTypeCopy -> BotButton.Copy(text, r, c, type.copy_text.orEmpty())
            is TL_keyboard.TL_inlineButtonTypeUserProfile -> BotButton.UserProfile(text, r, c, type.user_id)
            is TL_keyboard.TL_inlineButtonTypeGame -> BotButton.Game(text, r, c)
            is TL_keyboard.TL_inlineButtonTypeBuy -> BotButton.Buy(text, r, c)
            is TL_keyboard.TL_inlineButtonTypeDisabled -> BotButton.Disabled(text, r, c)
            else -> BotButton.Unsupported(text, r, c)
        }
    }

    private fun parseReplyKeyboard(markup: TLRPC.TL_replyKeyboardMarkup): ReplyKeyboard? {
        val rows = markup.rows.orEmpty().mapNotNull { row ->
            row?.buttons.orEmpty().mapNotNull { button ->
                button ?: return@mapNotNull null
                val kind = when (button.type) {
                    null, is TL_keyboard.TL_buttonTypeDefault -> ReplyButton.Kind.TEXT
                    is TL_keyboard.TL_buttonTypeRequestPhone -> ReplyButton.Kind.REQUEST_PHONE
                    is TL_keyboard.TL_buttonTypeRequestGeoLocation -> ReplyButton.Kind.REQUEST_LOCATION
                    is TL_keyboard.TL_buttonTypeRequestPoll -> ReplyButton.Kind.REQUEST_POLL
                    is TL_keyboard.TL_buttonTypeRequestPeer -> ReplyButton.Kind.REQUEST_PEER
                    is TL_keyboard.TL_buttonTypeSimpleWebView -> ReplyButton.Kind.WEB_APP
                    else -> ReplyButton.Kind.OTHER
                }
                ReplyButton(button.text.orEmpty(), kind)
            }.takeIf { it.isNotEmpty() }
        }
        if (rows.isEmpty()) return null
        return ReplyKeyboard(
            rows = rows,
            resize = markup.resize,
            singleUse = markup.single_use,
            persistent = markup.is_persistent,
            placeholder = markup.placeholder?.takeIf { it.isNotEmpty() },
        )
    }

    // ---- video ---------------------------------------------------------------------------------

    private fun parseVideo(messageObject: MessageObject): BotVideo? {
        val media = messageObject.messageOwner?.media as? TLRPC.TL_messageMediaDocument ?: return null
        val doc = media.document ?: return null
        if (doc is TLRPC.TL_documentEmpty) return null
        if (messageObject.isRoundVideo || messageObject.isGif) return null

        var videoAttr: TLRPC.TL_documentAttributeVideo? = null
        for (attr in doc.attributes.orEmpty()) {
            if (attr is TLRPC.TL_documentAttributeVideo) videoAttr = attr
            if (attr is TLRPC.TL_documentAttributeAnimated || attr is TLRPC.TL_documentAttributeSticker) return null
        }
        val mime = doc.mime_type.orEmpty().lowercase()
        val fileName = FileLoader.getDocumentFileName(doc).orEmpty()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val isVideo = videoAttr != null || mime.startsWith("video/") ||
            (mime == "application/octet-stream" && ext in VIDEO_EXTENSIONS)
        if (!isVideo) return null

        return BotVideo(
            documentId = doc.id,
            fileName = fileName,
            mimeType = doc.mime_type.orEmpty(),
            sizeBytes = doc.size,
            durationSeconds = videoAttr?.duration ?: MessageObject.getDocumentDuration(doc),
            width = videoAttr?.w ?: 0,
            height = videoAttr?.h ?: 0,
            supportsStreaming = videoAttr?.supports_streaming ?: false,
            isDocument = videoAttr == null,
            thumbnail = parseThumbnail(doc),
        ).withDocument(doc)
    }

    private fun parseThumbnail(doc: TLRPC.Document): BotThumbnail? {
        val thumbs = doc.thumbs ?: return null
        if (thumbs.isEmpty()) return null
        val inline = thumbs.firstOrNull { it is TLRPC.TL_photoStrippedSize } ?: thumbs.firstOrNull { it is TLRPC.TL_photoCachedSize }
        val sharpCandidates = ArrayList(thumbs.filter { it !is TLRPC.TL_photoStrippedSize && it !is TLRPC.TL_photoPathSize })
        val sharp = FileLoader.getClosestPhotoSizeWithSize(sharpCandidates, 320) ?: inline ?: return null
        return BotThumbnail(
            width = sharp.w,
            height = sharp.h,
            sizeBytes = sharp.size,
            type = sharp.type.orEmpty(),
            inlineBytes = inline?.bytes,
            isStripped = inline is TLRPC.TL_photoStrippedSize,
        ).withPhotoSize(sharp)
    }
}
