package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.chat.ChatInputViewsContainer;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vibgram: typing indicator at the bottom of the timeline, like Vibe.
 * A small picture of who is typing and three bouncing dots, no background.
 * Dots are white in dark mode and black in light mode.
 */
@SuppressLint("ViewConstructor")
public class VibeTypingView extends View implements NotificationCenter.NotificationCenterDelegate {

    private final int currentAccount;
    private final long dialogId;
    private final long threadId;
    private final ChatInputViewsContainer container;

    private final ImageReceiver avatarImage = new ImageReceiver(this);
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long typingUserId;
    private boolean typing;
    private float alpha;
    private long lastFrame;

    public VibeTypingView(Context context, int currentAccount, long dialogId, long threadId, ChatInputViewsContainer container) {
        super(context);
        this.currentAccount = currentAccount;
        this.dialogId = dialogId;
        this.threadId = threadId;
        this.container = container;
        setClickable(false);
        setFocusable(false);
        avatarImage.setRoundRadius(dp(10));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        avatarImage.onAttachedToWindow();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.updateInterfaces);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        avatarImage.onDetachedFromWindow();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.updateInterfaces && args.length > 0 && args[0] instanceof Integer) {
            int mask = (Integer) args[0];
            if ((mask & MessagesController.UPDATE_MASK_USER_PRINT) != 0) {
                update();
            }
        }
    }

    private void update() {
        long found = 0;
        long self = UserConfig.getInstance(currentAccount).getClientUserId();
        ConcurrentHashMap<Integer, ArrayList<MessagesController.PrintingUser>> threads =
                MessagesController.getInstance(currentAccount).printingUsers.get(dialogId);
        if (threads != null) {
            ArrayList<MessagesController.PrintingUser> users = threads.get((int) threadId);
            if (users != null) {
                for (int i = 0; i < users.size(); i++) {
                    MessagesController.PrintingUser pu = users.get(i);
                    if (pu != null && pu.userId != self) {
                        found = pu.userId;
                        break;
                    }
                }
            }
        }
        boolean nowTyping = found != 0;
        if (nowTyping && found != typingUserId) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(found);
            if (user != null) {
                avatarDrawable.setInfo(currentAccount, user);
                avatarImage.setForUserOrChat(user, avatarDrawable);
            }
        }
        if (nowTyping) {
            typingUserId = found;
        }
        if (nowTyping != typing) {
            typing = nowTyping;
            lastFrame = 0;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.elapsedRealtime();
        float dt = lastFrame == 0 ? 16f : Math.min(48f, now - lastFrame);
        lastFrame = now;
        float target = typing ? 1f : 0f;
        if (alpha != target) {
            float step = dt / 180f;
            alpha = typing ? Math.min(1f, alpha + step) : Math.max(0f, alpha - step);
        }
        if (alpha <= 0f) {
            return;
        }

        float bubbleTop = container != null ? container.getInputBubbleTop() : getHeight() - dp(60);
        float cy = bubbleTop - dp(14) + (1f - alpha) * dp(6);
        float x = dp(16);

        if (typingUserId != 0) {
            avatarImage.setAlpha(alpha);
            avatarImage.setImageCoords(x, cy - dp(10), dp(20), dp(20));
            avatarImage.draw(canvas);
            x += dp(28);
        }

        int color = Theme.isCurrentThemeDark() ? 0xFFFFFFFF : 0xFF000000;
        float r = dp(3);
        for (int i = 0; i < 3; i++) {
            double phase = (now % 1200) / 1200.0 * Math.PI * 2 - i * 0.7;
            float bounce = (float) Math.max(0, Math.sin(phase));
            dotPaint.setColor(color);
            dotPaint.setAlpha((int) (alpha * (150 + 105 * bounce)));
            canvas.drawCircle(x + r + i * dp(9), cy - bounce * dp(4), r, dotPaint);
        }
        invalidate();
    }
}
