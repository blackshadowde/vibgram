package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.VibeLayout;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Stories.recorder.StoryRecorder;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Vibgram home: a Discord style rail on the left and a list pane next to it.
 *
 * Rail: hub (channels, bots, saved) · pinned · people (circles) · groups (squares) · plus.
 * Pane: what the selected item holds (# chat, secret chats, topics, channels and bots).
 * Bottom: your own picture, name and settings.
 *
 * It sits on top of the classic chat list, which stays underneath for search,
 * folders and forwarding. "Classic chat list" in the plus menu shows it; back returns here.
 */
@SuppressLint("ViewConstructor")
public class VibeHomeView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int BLURPLE = 0xFF5865F2;
    private static final int RED = 0xFFF23F43;
    private static final int GREEN = 0xFF23A55A;

    private static final long HUB_ID = Long.MIN_VALUE + 7;

    private static final int RAIL_HUB = 0;
    private static final int RAIL_DIVIDER = 1;
    private static final int RAIL_PEER = 2;
    private static final int RAIL_PLUS = 3;

    private static final int PANE_HEADER = 0;
    private static final int PANE_SECTION = 1;
    private static final int PANE_ROW = 2;

    private final BaseFragment fragment;
    private final int currentAccount;

    private final RecyclerListView railList;
    private final RecyclerListView paneList;
    private final RailAdapter railAdapter = new RailAdapter();
    private final PaneAdapter paneAdapter = new PaneAdapter();
    private final View railBackground;
    private final LinearLayout paneColumn;
    private final FrameLayout userBar;
    private final BackupImageView barAvatar;
    private final AvatarDrawable barAvatarDrawable = new AvatarDrawable();
    private final TextView barName;
    private final TextView barStatus;
    private final ImageView barGear;
    private ImageView barBell;
    private FrameLayout barAvatarRing;
    private View barOnlineDot;
    private TextView searchField;

    private final ArrayList<RailItem> rail = new ArrayList<>();
    private final ArrayList<PaneItem> pane = new ArrayList<>();
    private final ArrayList<Long> hubDialogs = new ArrayList<>();
    private final HashMap<Long, ArrayList<Long>> secretByUser = new HashMap<>();
    private long selectedId = HUB_ID;
    private boolean reloadPosted;

    private final Runnable reloadRunnable = () -> {
        reloadPosted = false;
        reload();
    };

    public VibeHomeView(Context context, BaseFragment fragment) {
        super(context);
        this.fragment = fragment;
        this.currentAccount = fragment.getCurrentAccount();
        setClickable(true);

        railBackground = new View(context);
        addView(railBackground, LayoutHelper.createFrame(72, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP));

        railList = new RecyclerListView(context);
        railList.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        railList.setAdapter(railAdapter);
        railList.setVerticalScrollBarEnabled(false);
        railList.setClipToPadding(false);
        railList.setPadding(0, dp(8), 0, dp(104)); // room for the floating profile bar
        railList.setOnItemClickListener((view, position) -> onRailClick(view, position));
        railList.setOnItemLongClickListener((view, position) -> onRailLongClick(view, position));
        addView(railList, LayoutHelper.createFrame(72, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 0));

        paneColumn = new LinearLayout(context);
        paneColumn.setOrientation(LinearLayout.VERTICAL);
        addView(paneColumn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 72, 0, 0, 0));

        paneList = new RecyclerListView(context);
        paneList.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        paneList.setAdapter(paneAdapter);
        paneList.setVerticalScrollBarEnabled(false);
        paneList.setClipToPadding(false);
        paneList.setPadding(0, dp(4), 0, dp(104));
        paneList.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= pane.size()) {
                return;
            }
            PaneItem item = pane.get(position);
            if (item.action != null) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                item.action.run();
            }
        });
        searchField = new TextView(context);
        searchField.setText("Find or start a conversation");
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchField.setSingleLine(true);
        searchField.setGravity(Gravity.CENTER_VERTICAL);
        searchField.setPadding(dp(14), 0, dp(14), 0);
        searchField.setCompoundDrawablePadding(dp(12));
        searchField.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            showClassic();
        });
        paneColumn.addView(searchField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 12, 12, 12, 4));
        paneColumn.addView(paneList, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // Bottom user bar, like Vibe: a big picture overlapping a rounded pill
        // with your name, status, notifications and settings.
        FrameLayout barHolder = new FrameLayout(context);
        barHolder.setClipChildren(false);

        userBar = new FrameLayout(context);
        barName = new TextView(context);
        barName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        barName.setTypeface(AndroidUtilities.bold());
        barName.setSingleLine(true);
        barName.setEllipsize(TextUtils.TruncateAt.END);
        userBar.addView(barName, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 52, 10, 100, 0));
        barStatus = new TextView(context);
        barStatus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        barStatus.setSingleLine(true);
        barStatus.setText("Online");
        userBar.addView(barStatus, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 52, 34, 100, 0));
        barBell = new ImageView(context);
        barBell.setScaleType(ImageView.ScaleType.CENTER);
        barBell.setImageResource(R.drawable.msg_notifications);
        barBell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        barBell.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            fragment.presentFragment(new NotificationsSettingsActivity());
        });
        userBar.addView(barBell, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 52, 0));
        barGear = new ImageView(context);
        barGear.setScaleType(ImageView.ScaleType.CENTER);
        barGear.setImageResource(R.drawable.msg_settings);
        barGear.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        barGear.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            fragment.presentFragment(new SettingsActivity());
        });
        userBar.addView(barGear, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        userBar.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            fragment.presentFragment(new SettingsActivity());
        });
        barHolder.addView(userBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.LEFT | Gravity.BOTTOM, 34, 0, 0, 0));

        // Picture with a ring in the rail colour and a green online dot.
        barAvatarRing = new FrameLayout(context);
        barAvatar = new BackupImageView(context);
        barAvatar.setRoundRadius(dp(20));
        barAvatarRing.addView(barAvatar, LayoutHelper.createFrame(64, 64, Gravity.CENTER));
        barOnlineDot = new View(context);
        barAvatarRing.addView(barOnlineDot, LayoutHelper.createFrame(22, 22, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 2, 2));
        barAvatarRing.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            fragment.presentFragment(new SettingsActivity());
        });
        barAvatarRing.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (fragment.getParentActivity() != null) {
                StoryRecorder.getInstance(fragment.getParentActivity(), currentAccount).open(null);
            }
            return true;
        });
        barHolder.addView(barAvatarRing, LayoutHelper.createFrame(74, 74, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, -5));
        addView(barHolder, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 80, Gravity.LEFT | Gravity.BOTTOM, 8, 0, 8, 10));

        updateColors();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.addObserver(this, NotificationCenter.dialogsNeedReload);
        nc.addObserver(this, NotificationCenter.updateInterfaces);
        nc.addObserver(this, NotificationCenter.topicsDidLoaded);
        nc.addObserver(this, NotificationCenter.storiesUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        reload();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.removeObserver(this, NotificationCenter.dialogsNeedReload);
        nc.removeObserver(this, NotificationCenter.updateInterfaces);
        nc.removeObserver(this, NotificationCenter.topicsDidLoaded);
        nc.removeObserver(this, NotificationCenter.storiesUpdated);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        AndroidUtilities.cancelRunOnUIThread(reloadRunnable);
        reloadPosted = false;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            updateColors();
            railAdapter.notifyDataSetChanged();
            paneAdapter.notifyDataSetChanged();
            return;
        }
        scheduleReload();
    }

    private void scheduleReload() {
        if (reloadPosted) {
            return;
        }
        reloadPosted = true;
        AndroidUtilities.runOnUIThread(reloadRunnable, 150);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int top = AndroidUtilities.statusBarHeight;
        int bottom = AndroidUtilities.navigationBarHeight;
        if (getPaddingTop() != top || getPaddingBottom() != bottom) {
            setPadding(0, top, 0, bottom);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void updateColors() {
        int railColor = VibeLayout.rail();
        int paneColor = VibeLayout.bg();
        setBackgroundColor(railColor);
        railBackground.setBackgroundColor(railColor);
        paneColumn.setBackgroundColor(paneColor);
        GradientDrawable paneShape = new GradientDrawable();
        paneShape.setColor(paneColor);
        paneShape.setCornerRadii(new float[]{dp(16), dp(16), 0, 0, 0, 0, 0, 0});
        paneColumn.setBackground(paneShape);
        GradientDrawable barShape = new GradientDrawable();
        barShape.setColor(VibeLayout.card());
        barShape.setCornerRadius(dp(32));
        userBar.setBackground(barShape);
        GradientDrawable searchShape = new GradientDrawable();
        searchShape.setColor(VibeLayout.rail());
        searchShape.setCornerRadius(dp(12));
        searchField.setBackground(searchShape);
        searchField.setTextColor(VibeLayout.text2());
        Drawable searchIcon = getContext().getResources().getDrawable(R.drawable.msg_search).mutate();
        searchIcon.setColorFilter(new PorterDuffColorFilter(VibeLayout.text2(), PorterDuff.Mode.SRC_IN));
        searchField.setCompoundDrawablesWithIntrinsicBounds(searchIcon, null, null, null);
        barName.setTextColor(VibeLayout.text());
        barStatus.setTextColor(VibeLayout.text2());
        barGear.setColorFilter(new PorterDuffColorFilter(VibeLayout.text2(), PorterDuff.Mode.SRC_IN));
        barBell.setColorFilter(new PorterDuffColorFilter(VibeLayout.text2(), PorterDuff.Mode.SRC_IN));
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(24));
        ring.setColor(VibeLayout.bg());
        barAvatarRing.setBackground(ring);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(GREEN);
        dot.setStroke(dp(4), VibeLayout.bg());
        barOnlineDot.setBackground(dot);
    }

    // ------------------------------------------------------------------ data

    private MessagesController mc() {
        return MessagesController.getInstance(currentAccount);
    }

    private void reload() {
        MessagesController mc = mc();
        rail.clear();
        hubDialogs.clear();
        secretByUser.clear();

        ArrayList<RailItem> pinned = new ArrayList<>();
        ArrayList<RailItem> people = new ArrayList<>();
        ArrayList<RailItem> groups = new ArrayList<>();
        ArrayList<Long> seenUsers = new ArrayList<>();
        int hubUnread = 0;

        ArrayList<TLRPC.Dialog> dialogs = mc.getDialogs(0);
        for (int i = 0; i < dialogs.size(); i++) {
            TLRPC.Dialog d = dialogs.get(i);
            if (d == null) {
                continue;
            }
            long did = d.id;
            if (DialogObject.isFolderDialogId(did)) {
                continue;
            }
            if (DialogObject.isEncryptedDialog(did)) {
                TLRPC.EncryptedChat enc = mc.getEncryptedChat(DialogObject.getEncryptedChatId(did));
                if (enc == null) {
                    continue;
                }
                ArrayList<Long> list = secretByUser.get(enc.user_id);
                if (list == null) {
                    list = new ArrayList<>();
                    secretByUser.put(enc.user_id, list);
                }
                list.add(did);
                continue;
            }
            if (DialogObject.isUserDialog(did)) {
                TLRPC.User user = mc.getUser(did);
                if (user == null) {
                    continue;
                }
                if (UserObject.isUserSelf(user) || user.bot || UserObject.isReplyUser(user) || UserObject.isService(user.id)) {
                    hubDialogs.add(did);
                    hubUnread += d.unread_count;
                    continue;
                }
                RailItem item = RailItem.peer(did, user, d, false);
                seenUsers.add(did);
                (d.pinned ? pinned : people).add(item);
            } else {
                TLRPC.Chat chat = mc.getChat(-did);
                if (chat == null) {
                    continue;
                }
                if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                    hubDialogs.add(did);
                    if (!mc.isDialogMuted(did, 0)) {
                        hubUnread += d.unread_count;
                    }
                    continue;
                }
                RailItem item = RailItem.peer(did, chat, d, true);
                (d.pinned ? pinned : groups).add(item);
            }
        }
        // People you only talk to in secret chats still get a picture on the rail.
        for (Long userId : secretByUser.keySet()) {
            if (seenUsers.contains(userId)) {
                continue;
            }
            TLRPC.User user = mc.getUser(userId);
            if (user != null) {
                people.add(RailItem.peer(userId, user, null, false));
            }
        }

        int now = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        RailItem hub = new RailItem(RAIL_HUB);
        hub.dialogId = HUB_ID;
        hub.unread = hubUnread;
        rail.add(hub);
        rail.add(new RailItem(RAIL_DIVIDER));
        for (RailItem it : pinned) {
            fill(it, now);
            rail.add(it);
        }
        for (RailItem it : people) {
            fill(it, now);
            rail.add(it);
        }
        if (!groups.isEmpty()) {
            rail.add(new RailItem(RAIL_DIVIDER));
            for (RailItem it : groups) {
                fill(it, now);
                rail.add(it);
            }
        }
        rail.add(new RailItem(RAIL_PLUS));

        // Keep the selection if it still exists.
        boolean found = selectedId == HUB_ID;
        for (int i = 0; i < rail.size() && !found; i++) {
            if (rail.get(i).kind == RAIL_PEER && rail.get(i).dialogId == selectedId) {
                found = true;
            }
        }
        if (!found) {
            selectedId = HUB_ID;
        }
        railAdapter.notifyDataSetChanged();
        buildPane();
        updateUserBar();
    }

    private void fill(RailItem it, int now) {
        if (it.peer instanceof TLRPC.User) {
            TLRPC.User u = (TLRPC.User) it.peer;
            it.online = u.status != null && u.status.expires > now;
        }
        it.story = mc().getStoriesController().hasStories(it.dialogId);
        it.muted = mc().isDialogMuted(it.dialogId, 0);
    }

    private void updateUserBar() {
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (me != null) {
            barAvatarDrawable.setInfo(currentAccount, me);
            barAvatar.setForUserOrChat(me, barAvatarDrawable);
            barName.setText(UserObject.getUserName(me));
        }
    }

    private void buildPane() {
        pane.clear();
        MessagesController mc = mc();
        if (selectedId == HUB_ID) {
            pane.add(PaneItem.header("Channels & bots", hubDialogs.size() + " chats", null));
            long self = UserConfig.getInstance(currentAccount).getClientUserId();
            TLRPC.User me = mc.getUser(self);
            pane.add(PaneItem.row("#", "saved-messages", null, me, 0, () -> openChat(self)));
            ArrayList<Long> channels = new ArrayList<>();
            ArrayList<Long> bots = new ArrayList<>();
            for (Long did : hubDialogs) {
                if (did == self) {
                    continue;
                }
                (did < 0 ? channels : bots).add(did);
            }
            if (!channels.isEmpty()) {
                pane.add(PaneItem.section("CHANNELS"));
                for (Long did : channels) {
                    TLRPC.Chat chat = mc.getChat(-did);
                    if (chat == null) {
                        continue;
                    }
                    final long id = did;
                    pane.add(PaneItem.row("#", slug(chat.title), null, chat, unreadOf(did), () -> openChat(id)));
                }
            }
            if (!bots.isEmpty()) {
                pane.add(PaneItem.section("BOTS"));
                for (Long did : bots) {
                    TLRPC.User user = mc.getUser(did);
                    if (user == null) {
                        continue;
                    }
                    final long id = did;
                    pane.add(PaneItem.row("#", slug(UserObject.getUserName(user)), null, user, unreadOf(did), () -> openChat(id)));
                }
            }
            pane.add(PaneItem.section("MORE"));
            pane.add(PaneItem.row("📦", "Archive", "Long-press the top icon too", null, 0, this::openArchive));
        } else if (selectedId > 0) {
            TLRPC.User user = mc.getUser(selectedId);
            if (user != null) {
                String status = LocaleController.formatUserStatus(currentAccount, user);
                pane.add(PaneItem.header(UserObject.getUserName(user), status, user));
                final long id = selectedId;
                pane.add(PaneItem.row("#", "chat", null, null, unreadOf(id), () -> openChat(id)));
                ArrayList<Long> secrets = secretByUser.get(id);
                if (secrets != null) {
                    for (Long encId : secrets) {
                        final long eid = encId;
                        pane.add(PaneItem.row("🔒", "secret-chat", "End-to-end encrypted on this phone", null, unreadOf(eid), () -> openChat(eid)));
                    }
                }
                pane.add(PaneItem.section("INFO"));
                pane.add(PaneItem.row("👤", "Profile", null, null, 0, () -> openProfile(id)));
            }
        } else {
            long chatId = -selectedId;
            TLRPC.Chat chat = mc.getChat(chatId);
            if (chat != null) {
                String sub = chat.participants_count > 0 ? LocaleController.formatPluralString("Members", chat.participants_count) : "Group";
                pane.add(PaneItem.header(chat.title, sub, chat));
                if (ChatObject.isForum(chat)) {
                    ArrayList<TLRPC.TL_forumTopic> topics = mc.getTopicsController().getTopics(chatId);
                    if (topics == null || topics.isEmpty()) {
                        mc.getTopicsController().loadTopics(chatId);
                        pane.add(PaneItem.row("#", "all-topics", "Loading topics…", null, 0, () -> openTopics(chatId)));
                    } else {
                        pane.add(PaneItem.section("TOPICS"));
                        for (int i = 0; i < topics.size(); i++) {
                            final TLRPC.TL_forumTopic topic = topics.get(i);
                            if (topic == null || topic.hidden) {
                                continue;
                            }
                            pane.add(PaneItem.row("#", slug(topic.title), null, null, topic.unread_count, () -> ForumUtilities.openTopic(fragment, chatId, topic, 0)));
                        }
                    }
                } else {
                    final long id = selectedId;
                    pane.add(PaneItem.row("#", "chat", null, null, unreadOf(id), () -> openChat(id)));
                }
                pane.add(PaneItem.section("INFO"));
                pane.add(PaneItem.row("👥", "Members & info", null, null, 0, () -> openProfile(-chatId)));
            }
        }
        paneAdapter.notifyDataSetChanged();
    }

    private int unreadOf(long did) {
        TLRPC.Dialog d = mc().dialogs_dict.get(did);
        return d == null ? 0 : d.unread_count;
    }

    private static String slug(String title) {
        if (title == null) {
            return "";
        }
        String s = title.trim().toLowerCase().replaceAll("\\s+", "-");
        return s.isEmpty() ? title : s;
    }

    // ------------------------------------------------------------------ actions

    private void openChat(long did) {
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(did)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
        } else if (did > 0) {
            args.putLong("user_id", did);
        } else {
            args.putLong("chat_id", -did);
        }
        fragment.presentFragment(new ChatActivity(args));
    }

    private void openTopics(long chatId) {
        Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        fragment.presentFragment(new TopicsFragment(args));
    }

    private void openProfile(long did) {
        Bundle args = new Bundle();
        if (did > 0) {
            args.putLong("user_id", did);
        } else {
            args.putLong("chat_id", -did);
        }
        fragment.presentFragment(new ProfileActivity(args));
    }

    private void openArchive() {
        Bundle args = new Bundle();
        args.putInt("folderId", 1);
        fragment.presentFragment(new DialogsActivity(args));
    }

    private void onRailClick(View view, int position) {
        if (position < 0 || position >= rail.size()) {
            return;
        }
        RailItem item = rail.get(position);
        if (item.kind == RAIL_DIVIDER) {
            return;
        }
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (item.kind == RAIL_PLUS) {
            showPlusMenu();
            return;
        }
        if (selectedId == item.dialogId) {
            if (item.kind == RAIL_PEER && !ChatObject.isForum(item.peer instanceof TLRPC.Chat ? (TLRPC.Chat) item.peer : null)) {
                openChat(item.dialogId); // second tap opens the chat
            }
            return;
        }
        selectedId = item.dialogId;
        railAdapter.notifyDataSetChanged();
        buildPane();
        paneList.scrollToPosition(0);
    }

    private boolean onRailLongClick(View view, int position) {
        if (position < 0 || position >= rail.size()) {
            return false;
        }
        RailItem item = rail.get(position);
        if (item.kind == RAIL_HUB) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            openArchive();
            return true;
        }
        if (item.kind != RAIL_PEER) {
            return false;
        }
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (item.story && fragment.getParentActivity() != null) {
            fragment.getOrCreateStoryViewer().open(fragment.getParentActivity(), item.dialogId, (dialogId, messageId, storyId, type, holder) -> false);
            return true;
        }
        showPeerMenu(item);
        return true;
    }

    private void showPeerMenu(RailItem item) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        final long did = item.dialogId;
        final boolean pinned = item.pinned;
        final boolean muted = item.muted;
        CharSequence[] items = new CharSequence[]{
                pinned ? "Unpin" : "Pin",
                muted ? "Unmute" : "Mute",
                "Archive",
                did > 0 ? "Profile" : "Members & info"
        };
        int[] icons = new int[]{
                pinned ? R.drawable.msg_unpin : R.drawable.msg_pin,
                muted ? R.drawable.msg_unmute : R.drawable.msg_mute,
                R.drawable.msg_archive,
                R.drawable.msg_openprofile
        };
        BottomSheet.Builder builder = new BottomSheet.Builder(fragment.getParentActivity());
        builder.setItems(items, icons, (dialog, which) -> {
            MessagesController mc = mc();
            if (which == 0) {
                mc.pinDialog(did, !pinned, null, 0);
            } else if (which == 1) {
                NotificationsController.getInstance(currentAccount).muteDialog(did, 0, !muted);
            } else if (which == 2) {
                mc.addDialogToFolder(did, 1, -1, 0);
            } else if (which == 3) {
                openProfile(did);
            }
            scheduleReload();
        });
        fragment.showDialog(builder.create());
    }

    private void showPlusMenu() {
        if (fragment.getParentActivity() == null) {
            return;
        }
        CharSequence[] items = new CharSequence[]{
                "New message", "New group", "New channel", "Search", "Classic chat list"
        };
        int[] icons = new int[]{
                R.drawable.msg_message, R.drawable.msg_groups, R.drawable.msg_channel, R.drawable.msg_search, R.drawable.msg_list
        };
        BottomSheet.Builder builder = new BottomSheet.Builder(fragment.getParentActivity());
        builder.setItems(items, icons, (dialog, which) -> {
            if (which == 0) {
                Bundle args = new Bundle();
                args.putBoolean("destroyAfterSelect", true);
                fragment.presentFragment(new ContactsActivity(args));
            } else if (which == 1) {
                fragment.presentFragment(new GroupCreateActivity(new Bundle()));
            } else if (which == 2) {
                Bundle args = new Bundle();
                args.putInt("step", 0);
                fragment.presentFragment(new ChannelCreateActivity(args));
            } else if (which == 3 || which == 4) {
                showClassic();
            }
        });
        fragment.showDialog(builder.create());
    }

    /** Hide the rail and show Telegram's classic list underneath (search, folders, stories). */
    public void showClassic() {
        setVisibility(View.GONE);
    }

    /** @return true if the rail was hidden and is now shown again. */
    public boolean restoreFromClassic() {
        if (getVisibility() != View.VISIBLE) {
            setVisibility(View.VISIBLE);
            scheduleReload();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ models

    private static class RailItem {
        final int kind;
        long dialogId;
        TLObject peer;
        boolean square;
        boolean pinned;
        boolean muted;
        boolean online;
        boolean story;
        int unread;

        RailItem(int kind) {
            this.kind = kind;
        }

        static RailItem peer(long did, TLObject peer, TLRPC.Dialog d, boolean square) {
            RailItem it = new RailItem(RAIL_PEER);
            it.dialogId = did;
            it.peer = peer;
            it.square = square;
            if (d != null) {
                it.pinned = d.pinned;
                it.unread = d.unread_count;
            }
            return it;
        }
    }

    private static class PaneItem {
        final int kind;
        String icon;
        CharSequence title;
        CharSequence subtitle;
        TLObject peer;
        int unread;
        Runnable action;

        PaneItem(int kind) {
            this.kind = kind;
        }

        static PaneItem header(CharSequence title, CharSequence subtitle, TLObject peer) {
            PaneItem it = new PaneItem(PANE_HEADER);
            it.title = title;
            it.subtitle = subtitle;
            it.peer = peer;
            return it;
        }

        static PaneItem section(CharSequence title) {
            PaneItem it = new PaneItem(PANE_SECTION);
            it.title = title;
            return it;
        }

        static PaneItem row(String icon, CharSequence title, CharSequence subtitle, TLObject peer, int unread, Runnable action) {
            PaneItem it = new PaneItem(PANE_ROW);
            it.icon = icon;
            it.title = title;
            it.subtitle = subtitle;
            it.peer = peer;
            it.unread = unread;
            it.action = action;
            return it;
        }
    }

    // ------------------------------------------------------------------ rail

    private class RailAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != RAIL_DIVIDER;
        }

        @Override
        public int getItemCount() {
            return rail.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rail.get(position).kind;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = viewType == RAIL_DIVIDER ? new RailDivider(parent.getContext()) : new RailCell(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, viewType == RAIL_DIVIDER ? dp(14) : dp(58)));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof RailCell) {
                ((RailCell) holder.itemView).bind(rail.get(position));
            } else {
                holder.itemView.invalidate();
            }
        }
    }

    private static class RailDivider extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RailDivider(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(VibeLayout.divider());
            float cx = dp(36);
            float cy = getHeight() / 2f;
            canvas.drawRoundRect(cx - dp(16), cy - dp(1), cx + dp(16), cy + dp(1), dp(1), dp(1), paint);
        }
    }

    private class RailCell extends FrameLayout {
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final ImageView icon;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private RailItem item;

        RailCell(Context context) {
            super(context);
            setWillNotDraw(false);
            avatar = new BackupImageView(context);
            addView(avatar, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            addView(icon, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
            badgePaint.setTextSize(dp(11));
            badgePaint.setTypeface(AndroidUtilities.bold());
            badgePaint.setColor(0xFFFFFFFF);
        }

        void bind(RailItem item) {
            this.item = item;
            boolean selected = item.dialogId == selectedId && item.kind != RAIL_PLUS;
            if (item.kind == RAIL_PEER) {
                avatar.setVisibility(VISIBLE);
                icon.setVisibility(GONE);
                avatar.setRoundRadius(selected ? dp(12) : dp(16)); // rounded squares, a little squarer when selected
                avatarDrawable.setInfo(currentAccount, item.peer);
                avatar.setForUserOrChat(item.peer, avatarDrawable);
            } else {
                avatar.setVisibility(GONE);
                icon.setVisibility(VISIBLE);
                GradientDrawable bg = new GradientDrawable();
                boolean hub = item.kind == RAIL_HUB;
                bg.setColor(hub && selected ? BLURPLE : VibeLayout.cardHigh());
                bg.setCornerRadius(selected ? dp(12) : dp(16));
                icon.setBackground(bg);
                icon.setImageResource(hub ? R.drawable.msg_channel : R.drawable.msg_add);
                int tint = hub && selected ? 0xFFFFFFFF : (hub ? VibeLayout.text() : BLURPLE);
                icon.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
            }
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (item == null) {
                return;
            }
            float cx = dp(12 + 24);
            float cy = getHeight() / 2f;
            int text = VibeLayout.text();
            int railBg = VibeLayout.rail();

            // Selection pill on the left edge, like Discord.
            boolean selected = item.dialogId == selectedId && item.kind != RAIL_PLUS;
            if (selected || item.unread > 0 && !item.muted) {
                float h = selected ? dp(40) : dp(8);
                paint.setColor(text);
                rect.set(-dp(4), cy - h / 2f, dp(4), cy + h / 2f);
                canvas.drawRoundRect(rect, dp(4), dp(4), paint);
            }

            // Story ring.
            if (item.story) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(BLURPLE);
                rect.set(cx - dp(27), cy - dp(27), cx + dp(27), cy + dp(27));
                canvas.drawRoundRect(rect, dp(18), dp(18), paint);
                paint.setStyle(Paint.Style.FILL);
            }

            // Online dot.
            if (item.online) {
                float dx = cx + dp(17);
                float dy = cy + dp(17);
                paint.setColor(railBg);
                canvas.drawCircle(dx, dy, dp(8), paint);
                paint.setColor(GREEN);
                canvas.drawCircle(dx, dy, dp(5.5f), paint);
            }

            // Unread badge.
            if (item.unread > 0 && !item.muted) {
                String s = item.unread > 99 ? "99+" : String.valueOf(item.unread);
                float tw = badgePaint.measureText(s);
                float w = Math.max(dp(18), tw + dp(10));
                float right = cx + dp(26);
                float bottom = cy + dp(26);
                rect.set(right - w - dp(3), bottom - dp(18) - dp(3), right + dp(3), bottom + dp(3));
                paint.setColor(railBg);
                canvas.drawRoundRect(rect, dp(12), dp(12), paint);
                rect.set(right - w, bottom - dp(18), right, bottom);
                paint.setColor(RED);
                canvas.drawRoundRect(rect, dp(9), dp(9), paint);
                Paint.FontMetrics fm = badgePaint.getFontMetrics();
                canvas.drawText(s, rect.centerX() - tw / 2f, rect.centerY() - (fm.ascent + fm.descent) / 2f, badgePaint);
            }
        }
    }

    // ------------------------------------------------------------------ pane

    private class PaneAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == PANE_ROW;
        }

        @Override
        public int getItemCount() {
            return pane.size();
        }

        @Override
        public int getItemViewType(int position) {
            return pane.get(position).kind;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == PANE_HEADER) {
                view = new PaneHeader(parent.getContext());
            } else if (viewType == PANE_SECTION) {
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                tv.setTypeface(AndroidUtilities.bold());
                tv.setLetterSpacing(0.04f);
                tv.setPadding(dp(16), dp(18), dp(16), dp(6));
                view = tv;
            } else {
                view = new PaneRow(parent.getContext());
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            PaneItem item = pane.get(position);
            if (holder.itemView instanceof PaneHeader) {
                ((PaneHeader) holder.itemView).bind(item);
            } else if (holder.itemView instanceof PaneRow) {
                ((PaneRow) holder.itemView).bind(item);
            } else if (holder.itemView instanceof TextView) {
                TextView tv = (TextView) holder.itemView;
                tv.setText(item.title);
                tv.setTextColor(VibeLayout.text2());
            }
        }
    }

    private class PaneHeader extends LinearLayout {
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView title;
        private final TextView subtitle;

        PaneHeader(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(16), dp(16), dp(8));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(20));
            addView(avatar, LayoutHelper.createLinear(64, 64, 0, 0, 0, 10));
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(PaneItem item) {
            if (item.peer != null) {
                avatar.setVisibility(VISIBLE);
                boolean square = item.peer instanceof TLRPC.Chat;
                avatar.setRoundRadius(dp(20));
                avatarDrawable.setInfo(currentAccount, item.peer);
                avatar.setForUserOrChat(item.peer, avatarDrawable);
            } else {
                avatar.setVisibility(GONE);
            }
            title.setText(item.title);
            title.setTextColor(VibeLayout.text());
            subtitle.setText(item.subtitle);
            subtitle.setTextColor(VibeLayout.text2());
            subtitle.setVisibility(TextUtils.isEmpty(item.subtitle) ? GONE : VISIBLE);
        }
    }

    private class PaneRow extends FrameLayout {
        private final TextView icon;
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView title;
        private final TextView subtitle;
        private final TextView badge;

        PaneRow(Context context) {
            super(context);
            setMinimumHeight(dp(44));
            icon = new TextView(context);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            icon.setGravity(Gravity.CENTER);
            addView(icon, LayoutHelper.createFrame(28, 28, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(7));
            addView(avatar, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 50, 8, 52, 8));

            badge = new TextView(context);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            badge.setTypeface(AndroidUtilities.bold());
            badge.setTextColor(0xFFFFFFFF);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(6), 0, dp(6), 0);
            badge.setMinWidth(dp(20));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(RED);
            bg.setCornerRadius(dp(10));
            badge.setBackground(bg);
            addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 20, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        }

        void bind(PaneItem item) {
            int text = VibeLayout.text();
            int gray = VibeLayout.text2();
            if (item.peer != null) {
                icon.setVisibility(GONE);
                avatar.setVisibility(VISIBLE);
                avatarDrawable.setInfo(currentAccount, item.peer);
                avatar.setRoundRadius(dp(7));
                avatar.setForUserOrChat(item.peer, avatarDrawable);
            } else {
                avatar.setVisibility(GONE);
                icon.setVisibility(VISIBLE);
                icon.setText(item.icon);
                icon.setTextColor(gray);
            }
            boolean unread = item.unread > 0;
            title.setText(item.title);
            title.setTextColor(unread ? text : (Theme.isCurrentThemeDark() ? 0xFFB5BAC1 : 0xFF4E5058));
            title.setTypeface(unread ? AndroidUtilities.bold() : Typeface.DEFAULT);
            subtitle.setText(item.subtitle);
            subtitle.setTextColor(gray);
            subtitle.setVisibility(TextUtils.isEmpty(item.subtitle) ? GONE : VISIBLE);
            badge.setVisibility(unread ? VISIBLE : GONE);
            badge.setText(item.unread > 99 ? "99+" : String.valueOf(item.unread));
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        }
    }
}
