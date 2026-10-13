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
import org.telegram.messenger.VibePush;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MediaActivity;
import org.telegram.ui.Components.SharedMediaLayout;
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
    private static final int RAIL_STACK = 4;
    // Stacks (Telegram chat folders) get ids in their own range on the rail.
    private static final long STACK_BASE = Long.MIN_VALUE + 100000;

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
    private final ArrayList<Long> requests = new ArrayList<>();
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
        // Long-press a chat in the middle pane: pin, mute, mark read, archive, delete...
        paneList.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= pane.size() || pane.get(position).dialogId == 0) {
                return false;
            }
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showChatMenu(pane.get(position).dialogId);
            return true;
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
            VibeSearchSheet.show(fragment);
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
        barBell.setImageResource(R.drawable.vibe_ic_bell);
        barBell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        barBell.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            fragment.presentFragment(new NotificationsSettingsActivity());
        });
        userBar.addView(barBell, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 52, 0));
        barGear = new ImageView(context);
        barGear.setScaleType(ImageView.ScaleType.CENTER);
        barGear.setImageResource(R.drawable.vibe_ic_settings);
        barGear.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        barGear.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            fragment.presentFragment(new SettingsActivity());
        });
        userBar.addView(barGear, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        userBar.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            VibeOwnProfileSheet.show(fragment);
        });
        barHolder.addView(userBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.LEFT | Gravity.BOTTOM, 34, 0, 0, 0));

        // Picture with a ring in the rail colour and a green online dot.
        barAvatarRing = new FrameLayout(context);
        barAvatar = new BackupImageView(context);
        barAvatar.setRoundRadius(dp(32));
        barAvatarRing.addView(barAvatar, LayoutHelper.createFrame(64, 64, Gravity.CENTER));
        barOnlineDot = new View(context);
        barAvatarRing.addView(barOnlineDot, LayoutHelper.createFrame(22, 22, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 2, 2));
        barAvatarRing.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            VibeOwnProfileSheet.show(fragment);
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
        nc.addObserver(this, NotificationCenter.dialogFiltersUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        reload();
        AndroidUtilities.runOnUIThread(this::checkNotifications, 1500);
    }

    /**
     * Vibgram notifications: built in (a quiet always-on connection) by default,
     * or through the ntfy app when it is installed. Also applies privacy defaults
     * and clears an expired status.
     */
    private void checkNotifications() {
        if (!isAttachedToWindow() || fragment.getParentActivity() == null) {
            return;
        }
        org.telegram.messenger.VibePrivacy.applyDefaultsOnce(currentAccount);
        org.telegram.messenger.VibePrivacy.checkStatusExpiry(currentAccount);
        VibePush.applyMode(getContext());
        VibeUpdates.checkQuietly(fragment);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.removeObserver(this, NotificationCenter.dialogsNeedReload);
        nc.removeObserver(this, NotificationCenter.updateInterfaces);
        nc.removeObserver(this, NotificationCenter.topicsDidLoaded);
        nc.removeObserver(this, NotificationCenter.storiesUpdated);
        nc.removeObserver(this, NotificationCenter.dialogFiltersUpdated);
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
        Drawable searchIcon = getContext().getResources().getDrawable(R.drawable.vibe_ic_search).mutate();
        searchIcon.setColorFilter(new PorterDuffColorFilter(VibeLayout.text2(), PorterDuff.Mode.SRC_IN));
        searchField.setCompoundDrawablesWithIntrinsicBounds(searchIcon, null, null, null);
        barName.setTextColor(VibeLayout.text());
        barStatus.setTextColor(VibeLayout.text2());
        barGear.setColorFilter(new PorterDuffColorFilter(VibeLayout.text2(), PorterDuff.Mode.SRC_IN));
        barBell.setColorFilter(new PorterDuffColorFilter(VibeLayout.text2(), PorterDuff.Mode.SRC_IN));
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
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
        requests.clear();

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
                long other = enc.user_id;
                if (other == 0) {
                    long self = UserConfig.getInstance(currentAccount).getClientUserId();
                    other = enc.admin_id == self ? enc.participant_id : enc.admin_id;
                }
                if (other == 0) {
                    continue;
                }
                ArrayList<Long> list = secretByUser.get(other);
                if (list == null) {
                    list = new ArrayList<>();
                    secretByUser.put(other, list);
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
                if (org.telegram.messenger.VibeRequests.isRequest(currentAccount, did)) {
                    // Not a contact and you never wrote to them: waits in Message requests.
                    requests.add(did);
                    if (d.unread_count > 0) {
                        hubUnread++;
                    }
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
        ArrayList<RailItem> stacks = buildStacks(dialogs);
        if (!stacks.isEmpty()) {
            rail.addAll(stacks);
            rail.add(new RailItem(RAIL_DIVIDER));
        }
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
            if ((rail.get(i).kind == RAIL_PEER || rail.get(i).kind == RAIL_STACK) && rail.get(i).dialogId == selectedId) {
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

    // ------------------------------------------------------------------ stacks (chat folders)

    private static boolean isStackId(long id) {
        return id >= STACK_BASE && id < STACK_BASE + 100000;
    }

    private MessagesController.DialogFilter findStack(long id) {
        ArrayList<MessagesController.DialogFilter> filters = mc().getDialogFilters();
        for (int i = 0; i < filters.size(); i++) {
            MessagesController.DialogFilter f = filters.get(i);
            if (f != null && STACK_BASE + f.id == id) {
                return f;
            }
        }
        return null;
    }

    private ArrayList<Long> stackDialogs(MessagesController.DialogFilter f, ArrayList<TLRPC.Dialog> dialogs) {
        ArrayList<Long> out = new ArrayList<>();
        org.telegram.messenger.AccountInstance ai = org.telegram.messenger.AccountInstance.getInstance(currentAccount);
        for (int i = 0; i < dialogs.size(); i++) {
            TLRPC.Dialog d = dialogs.get(i);
            if (d == null || DialogObject.isFolderDialogId(d.id)) {
                continue;
            }
            try {
                if (f.includesDialog(ai, d.id)) {
                    out.add(d.id);
                }
            } catch (Throwable ignore) {
            }
        }
        return out;
    }

    private ArrayList<RailItem> buildStacks(ArrayList<TLRPC.Dialog> dialogs) {
        ArrayList<RailItem> out = new ArrayList<>();
        ArrayList<MessagesController.DialogFilter> filters = mc().getDialogFilters();
        for (int i = 0; i < filters.size(); i++) {
            MessagesController.DialogFilter f = filters.get(i);
            if (f == null || f.isDefault() || f.locked) {
                continue;
            }
            RailItem it = new RailItem(RAIL_STACK);
            it.dialogId = STACK_BASE + f.id;
            it.label = stackLabel(f.name);
            int unread = 0;
            for (Long did : stackDialogs(f, dialogs)) {
                TLRPC.Dialog d = mc().dialogs_dict.get(did);
                if (d != null && d.unread_count > 0 && !mc().isDialogMuted(did, 0)) {
                    unread++;
                }
            }
            it.unread = unread;
            out.add(it);
        }
        return out;
    }

    /** First emoji, or up to two initials, of the folder name. */
    private static String stackLabel(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "#";
        }
        String n = name.trim();
        int cp = n.codePointAt(0);
        if (!Character.isLetterOrDigit(cp)) {
            return new String(Character.toChars(cp));
        }
        String[] words = n.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty() && sb.length() < 2) {
                sb.appendCodePoint(Character.toUpperCase(w.codePointAt(0)));
            }
        }
        return sb.toString();
    }

    private void buildStackPane(MessagesController.DialogFilter f) {
        MessagesController mc = mc();
        ArrayList<Long> list = stackDialogs(f, mc.getDialogs(0));
        pane.add(PaneItem.header(f.name, list.size() + (list.size() == 1 ? " chat" : " chats"), null));
        for (Long did : list) {
            final long id = did;
            if (DialogObject.isEncryptedDialog(did)) {
                TLRPC.EncryptedChat enc = mc.getEncryptedChat(DialogObject.getEncryptedChatId(did));
                TLRPC.User u = enc != null ? mc.getUser(enc.user_id) : null;
                if (u != null) {
                    pane.add(PaneItem.row("#", UserObject.getUserName(u), "🔒 Secret chat", u, unreadOf(did), () -> openChat(id)).dlg(did));
                }
            } else if (did > 0) {
                TLRPC.User u = mc.getUser(did);
                if (u != null) {
                    pane.add(PaneItem.row("#", UserObject.getUserName(u), null, u, unreadOf(did), () -> openChat(id)).dlg(did));
                }
            } else {
                TLRPC.Chat c = mc.getChat(-did);
                if (c != null) {
                    pane.add(PaneItem.row("#", slug(c.title), null, c, unreadOf(did), () -> {
                        if (ChatObject.isForum(c)) {
                            openTopics(-id);
                        } else {
                            openChat(id);
                        }
                    }).dlg(did));
                }
            }
        }
        if (list.isEmpty()) {
            pane.add(PaneItem.row("#", "empty-stack", "Add chats to this stack", null, 0, () -> fragment.presentFragment(new FilterCreateActivity(f))));
        }
        pane.add(PaneItem.section("STACK"));
        pane.add(PaneItem.row("#", "edit-stack", "Name, chats and rules", null, 0, () -> fragment.presentFragment(new FilterCreateActivity(f))));
        pane.add(PaneItem.row("#", "reorder-stacks", "Drag to change the order", null, 0, () -> fragment.presentFragment(new FiltersSetupActivity())));
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
        if (isStackId(selectedId)) {
            MessagesController.DialogFilter f = findStack(selectedId);
            if (f != null) {
                buildStackPane(f);
            }
            paneAdapter.notifyDataSetChanged();
            return;
        }
        if (selectedId == HUB_ID) {
            pane.add(PaneItem.header("Channels & bots", hubDialogs.size() + " chats", null));
            if (!requests.isEmpty()) {
                pane.add(PaneItem.section("MESSAGE REQUESTS — " + requests.size()));
                for (Long rid : requests) {
                    TLRPC.User ru = mc.getUser(rid);
                    if (ru == null) {
                        continue;
                    }
                    final long r = rid;
                    pane.add(PaneItem.row("#", UserObject.getUserName(ru), org.telegram.messenger.VibeRequests.preview(currentAccount, r), ru, unreadOf(r),
                            () -> VibeRequestSheet.show(fragment, r, this::reload)));
                }
            }
            long self = UserConfig.getInstance(currentAccount).getClientUserId();
            TLRPC.User me = mc.getUser(self);
            pane.add(PaneItem.row("#", "saved-messages", null, me, 0, () -> openChat(self)).dlg(self));
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
                    pane.add(PaneItem.row("#", slug(chat.title), null, chat, unreadOf(did), () -> openChat(id)).dlg(did));
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
                    pane.add(PaneItem.row("#", slug(UserObject.getUserName(user)), null, user, unreadOf(did), () -> openChat(id)).dlg(did));
                }
            }
            pane.add(PaneItem.section("MORE"));
            pane.add(PaneItem.row("#", "archive", "Long-press the top icon too", null, 0, this::openArchive));
        } else if (selectedId > 0) {
            TLRPC.User user = mc.getUser(selectedId);
            if (user != null) {
                String status = LocaleController.formatUserStatus(currentAccount, user);
                pane.add(PaneItem.header(UserObject.getUserName(user), status, user));
                final long id = selectedId;
                // One person = one rail picture. Their normal chat and any secret chats
                // are channels inside it, never a second DM.
                boolean hasRegular = mc.dialogs_dict.get(id) != null;
                ArrayList<Long> secrets = secretByUser.get(id);
                boolean hasSecret = secrets != null && !secrets.isEmpty();
                if (hasRegular || !hasSecret) {
                    pane.add(PaneItem.row("#", "chat", null, null, unreadOf(id), () -> openChat(id)).dlg(id));
                }
                if (hasSecret) {
                    for (int si = 0; si < secrets.size(); si++) {
                        final long eid = secrets.get(si);
                        pane.add(PaneItem.row("#", si == 0 ? "secret-chat" : "secret-chat-" + (si + 1), "🔒 End-to-end encrypted on this phone", null, unreadOf(eid), () -> openChat(eid)).dlg(eid));
                    }
                }
                pane.add(PaneItem.section("INFO"));
                pane.add(PaneItem.row("#", "media", "Photos and videos", null, 0, () -> openMedia(id, SharedMediaLayout.TAB_PHOTOVIDEO)));
                pane.add(PaneItem.row("#", "files", null, null, 0, () -> openMedia(id, SharedMediaLayout.TAB_FILES)));
                pane.add(PaneItem.row("#", "links", null, null, 0, () -> openMedia(id, SharedMediaLayout.TAB_LINKS)));
                pane.add(PaneItem.row("#", "voice-messages", null, null, 0, () -> openMedia(id, SharedMediaLayout.TAB_VOICE)));
                pane.add(PaneItem.row("#", "pinned-messages", null, null, 0, () -> openPinned(id)));
                pane.add(PaneItem.row("#", "profile", null, null, 0, () -> VibeProfileSheet.show(fragment, user)));
                if (!hasSecret || !hasRegular) {
                    pane.add(PaneItem.section("MORE"));
                }
                if (!hasRegular && hasSecret) {
                    pane.add(PaneItem.row("#", "chat", "Start a normal cloud chat", null, 0, () -> openChat(id)));
                }
                if (!hasSecret && !user.bot && !UserObject.isUserSelf(user)) {
                    pane.add(PaneItem.row("#", "start-secret-chat", "End-to-end encrypted, this phone only", null, 0, () -> startSecret(user)));
                }
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
                    pane.add(PaneItem.row("#", "chat", null, null, unreadOf(id), () -> openChat(id)).dlg(id));
                }
                pane.add(PaneItem.section("INFO"));
                pane.add(PaneItem.row("#", "media", "Photos and videos", null, 0, () -> openMedia(-chatId, SharedMediaLayout.TAB_PHOTOVIDEO)));
                pane.add(PaneItem.row("#", "files", null, null, 0, () -> openMedia(-chatId, SharedMediaLayout.TAB_FILES)));
                pane.add(PaneItem.row("#", "links", null, null, 0, () -> openMedia(-chatId, SharedMediaLayout.TAB_LINKS)));
                pane.add(PaneItem.row("#", "pinned-messages", null, null, 0, () -> openPinned(-chatId)));
                pane.add(PaneItem.row("#", "members-and-info", null, null, 0, () -> openProfile(-chatId)));
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

    private void startSecret(TLRPC.User user) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle("Start secret chat?");
        b.setMessage("Messages in a secret chat are end-to-end encrypted and stay on this phone only. It will show up as #secret-chat under " + UserObject.getFirstName(user) + ".");
        b.setPositiveButton("Start", (d, w) -> org.telegram.messenger.SecretChatHelper.getInstance(currentAccount).startSecretChat(fragment.getParentActivity(), user));
        b.setNegativeButton("Cancel", null);
        fragment.showDialog(b.create());
    }

    private void openPinned(long did) {
        Bundle args = new Bundle();
        if (did > 0) {
            args.putLong("user_id", did);
        } else {
            args.putLong("chat_id", -did);
        }
        args.putBoolean("vibe_pinned", true);
        fragment.presentFragment(new ChatActivity(args));
    }

    // Swipe sideways on home with a DM or group selected: the pane follows your
    // finger, and past the threshold the chat slides in over home.
    private float swipeStartX, swipeStartY;
    private boolean swipeTracking, swipeDragging;
    private android.view.VelocityTracker swipeVelocity;

    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                swipeStartX = ev.getX();
                swipeStartY = ev.getY();
                swipeTracking = selectedId != HUB_ID && !isStackId(selectedId) && ev.getX() > dp(72);
                swipeDragging = false;
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                if (swipeTracking) {
                    float dx = ev.getX() - swipeStartX;
                    float dy = ev.getY() - swipeStartY;
                    if (Math.abs(dy) > dp(20) && Math.abs(dy) > Math.abs(dx)) {
                        swipeTracking = false;
                    } else if (Math.abs(dx) > dp(18) && Math.abs(dx) > Math.abs(dy) * 2) {
                        swipeDragging = true;
                        swipeStartX = ev.getX();
                        if (swipeVelocity == null) {
                            swipeVelocity = android.view.VelocityTracker.obtain();
                        }
                        swipeVelocity.clear();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                }
                break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                swipeTracking = false;
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (!swipeDragging) {
            return super.onTouchEvent(ev);
        }
        if (swipeVelocity != null) {
            swipeVelocity.addMovement(ev);
        }
        float dx = ev.getX() - swipeStartX;
        float width = Math.max(1, paneColumn.getWidth());
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_MOVE: {
                // The pane stays put; once the swipe is clearly sideways the chat slides in over home.
                if (Math.abs(dx) > width * 0.18f) {
                    swipeDragging = false;
                    swipeTracking = false;
                    if (swipeVelocity != null) {
                        swipeVelocity.recycle();
                        swipeVelocity = null;
                    }
                    openSelectedChat();
                }
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                float vx = 0;
                if (swipeVelocity != null) {
                    swipeVelocity.computeCurrentVelocity(1000);
                    vx = swipeVelocity.getXVelocity();
                    swipeVelocity.recycle();
                    swipeVelocity = null;
                }
                swipeDragging = false;
                swipeTracking = false;
                if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP && Math.abs(vx) > dp(700) && Math.signum(vx) == Math.signum(dx)) {
                    openSelectedChat();
                }
                return true;
            }
        }
        return true;
    }

    private void openSelectedChat() {
        if (selectedId == HUB_ID || isStackId(selectedId)) {
            return;
        }
        if (selectedId < 0) {
            TLRPC.Chat chat = mc().getChat(-selectedId);
            if (chat != null && ChatObject.isForum(chat)) {
                openTopics(-selectedId);
                return;
            }
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (selectedId > 0 && mc().dialogs_dict.get(selectedId) == null) {
            ArrayList<Long> secrets = secretByUser.get(selectedId);
            if (secrets != null && !secrets.isEmpty()) {
                openChat(secrets.get(0));
                return;
            }
        }
        openChat(selectedId);
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

    /** Shared media page (its own scrolling list) opened on one tab. */
    private void openMedia(long did, int tab) {
        Bundle args = new Bundle();
        args.putLong("dialog_id", did);
        args.putInt("start_from", tab);
        fragment.presentFragment(new MediaActivity(args, null));
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
            if (item.kind == RAIL_PEER && item.peer != null) {
                VibeProfileSheet.show(fragment, item.peer); // second tap: profile card slides up
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
        if (item.kind == RAIL_STACK) {
            MessagesController.DialogFilter f = findStack(item.dialogId);
            if (f != null) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                fragment.presentFragment(new FilterCreateActivity(f));
                return true;
            }
            return false;
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
        showChatMenu(item.dialogId);
    }

    /** Options for one chat, from the rail or the middle pane: a Discord-style sheet from the bottom. */
    private void showChatMenu(long did) {
        if (fragment.getParentActivity() == null || did == 0) {
            return;
        }
        MessagesController mc = mc();
        TLRPC.Dialog d = mc.dialogs_dict.get(did);
        final boolean pinned = d != null && d.pinned;
        final boolean muted = mc.isDialogMuted(did, 0);
        final boolean archived = d != null && d.folder_id == 1;
        final boolean unread = d != null && (d.unread_count > 0 || d.unread_mark);
        final boolean self = did == UserConfig.getInstance(currentAccount).getClientUserId();
        final boolean secret = DialogObject.isEncryptedDialog(did);
        final boolean isUser = did > 0 || secret;

        TLObject peer = null;
        CharSequence title = "";
        String username = null;
        if (secret) {
            TLRPC.EncryptedChat enc = mc.getEncryptedChat(DialogObject.getEncryptedChatId(did));
            TLRPC.User u = enc != null ? mc.getUser(enc.user_id) : null;
            peer = u;
            title = u != null ? "🔒 " + UserObject.getUserName(u) : "Secret chat";
        } else if (did > 0) {
            TLRPC.User u = mc.getUser(did);
            peer = u;
            title = self ? "Saved messages" : (u != null ? UserObject.getUserName(u) : "");
            username = u != null ? UserObject.getPublicUsername(u) : null;
        } else {
            TLRPC.Chat c = mc.getChat(-did);
            peer = c;
            title = c != null ? c.title : "";
            username = c != null ? ChatObject.getPublicUsername(c) : null;
        }
        final String link = username != null ? "https://t.me/" + username : null;

        ArrayList<java.util.List<VibeOptionsSheet.Opt>> groups = new ArrayList<>();
        groups.add(VibeOptionsSheet.list(
                unread ? new VibeOptionsSheet.Opt(R.drawable.vibe_ic_eye, "Mark as read", false, () -> {
                    mc.markDialogAsRead(did, d.top_message, d.top_message, d.last_message_date, false, 0, 0, true, 0);
                    scheduleReload();
                }) : null,
                new VibeOptionsSheet.Opt(pinned ? R.drawable.vibe_ic_unpin : R.drawable.vibe_ic_pin, pinned ? "Unpin" : "Pin to top", false, () -> {
                    mc.pinDialog(did, !pinned, null, 0);
                    scheduleReload();
                }),
                self ? null : new VibeOptionsSheet.Opt(muted ? R.drawable.vibe_ic_bell : R.drawable.vibe_ic_bell_off, muted ? "Unmute" : "Mute", false, () -> {
                    NotificationsController.getInstance(currentAccount).muteDialog(did, 0, !muted);
                    scheduleReload();
                }),
                self || secret ? null : new VibeOptionsSheet.Opt(R.drawable.vibe_ic_tune, "Notification settings", false, () -> {
                    Bundle args = new Bundle();
                    args.putLong("dialog_id", did);
                    fragment.presentFragment(new ProfileNotificationsActivity(args));
                })
        ));
        groups.add(VibeOptionsSheet.list(
                link != null ? new VibeOptionsSheet.Opt(R.drawable.vibe_ic_link, "Copy link", false, () -> {
                    AndroidUtilities.addToClipboard(link);
                    org.telegram.ui.Components.BulletinFactory.of(fragment).createCopyLinkBulletin().show();
                }) : null,
                self ? null : new VibeOptionsSheet.Opt(archived ? R.drawable.vibe_ic_unarchive : R.drawable.vibe_ic_archive, archived ? "Unarchive" : "Archive", false, () -> {
                    mc.addDialogToFolder(did, archived ? 0 : 1, -1, 0);
                    if (!archived) {
                        org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_archived, "Archived. Long-press the top icon to see your archive.").show();
                    }
                    scheduleReload();
                }),
                secret ? null : new VibeOptionsSheet.Opt(R.drawable.vibe_ic_account_circle, did > 0 ? "Profile" : "Members & info", false, () -> openProfile(did)),
                VibeLockUi.canUse(currentAccount, did) ? new VibeOptionsSheet.Opt(R.drawable.vibe_ic_lock,
                        org.telegram.messenger.VibeLock.isOn(currentAccount, did) ? "Vibe lock: on" : "Vibe lock", false,
                        () -> VibeLockUi.showMenu(fragment, currentAccount, did, null)) : null
        ));
        if (!self) {
            groups.add(VibeOptionsSheet.list(
                    new VibeOptionsSheet.Opt(isUser ? R.drawable.vibe_ic_delete : R.drawable.vibe_ic_logout, isUser ? "Delete chat" : (ChatObject.isChannelAndNotMegaGroup(-did, currentAccount) ? "Leave channel" : "Leave group"), true, () -> confirmDelete(did, isUser))
            ));
        }
        VibeOptionsSheet.show(fragment, currentAccount, peer, title, groups);
    }

    private void confirmDelete(long did, boolean isUser) {
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle(isUser ? "Delete chat?" : "Leave?");
        b.setMessage(isUser ? "This deletes the chat from this account." : "You will leave and it will be removed from your list.");
        b.setPositiveButton(isUser ? "Delete" : "Leave", (dd, w) -> {
            MessagesController mc = mc();
            if (!isUser) {
                mc.deleteParticipantFromChat(-did, UserConfig.getInstance(currentAccount).getCurrentUser());
            }
            mc.deleteDialog(did, 0);
            scheduleReload();
        });
        b.setNegativeButton("Cancel", null);
        AlertDialog dialog = b.create();
        fragment.showDialog(dialog);
        android.widget.TextView button = (android.widget.TextView) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(0xFFF87171);
        }
    }

    private void showPlusMenu() {
        if (fragment.getParentActivity() == null) {
            return;
        }
        CharSequence[] items = new CharSequence[]{
                "New message", "New group", "New channel", "Find people, bots or channels", "New stack"
        };
        int[] icons = new int[]{
                R.drawable.vibe_ic_chat_add, R.drawable.vibe_ic_group, R.drawable.vibe_ic_campaign, R.drawable.vibe_ic_search, R.drawable.vibe_ic_folder
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
            } else if (which == 3) {
                VibeSearchSheet.show(fragment);
            } else if (which == 4) {
                fragment.presentFragment(new FilterCreateActivity());
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
        String label;

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
        long dialogId; // set for rows that are a chat: long-press opens chat options

        PaneItem(int kind) {
            this.kind = kind;
        }

        PaneItem dlg(long did) {
            this.dialogId = did;
            return this;
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
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
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
            labelPaint.setTypeface(VibeLayout.boldFont());
        }

        void bind(RailItem item) {
            this.item = item;
            boolean selected = item.dialogId == selectedId && item.kind != RAIL_PLUS;
            if (item.kind == RAIL_PEER) {
                avatar.setVisibility(VISIBLE);
                icon.setVisibility(GONE);
                avatar.setRoundRadius(selected ? dp(12) : dp(16)); // rounded squares on the rail only
                avatarDrawable.setRoundRadius(selected ? dp(12) : dp(16));
                avatarDrawable.setInfo(currentAccount, item.peer);
                avatar.setForUserOrChat(item.peer, avatarDrawable);
            } else {
                avatar.setVisibility(GONE);
                icon.setVisibility(VISIBLE);
                GradientDrawable bg = new GradientDrawable();
                boolean hub = item.kind == RAIL_HUB;
                boolean stack = item.kind == RAIL_STACK;
                bg.setColor((hub || stack) && selected ? BLURPLE : VibeLayout.cardHigh());
                bg.setCornerRadius(selected ? dp(12) : dp(16));
                icon.setBackground(bg);
                if (stack) {
                    icon.setImageDrawable(null);
                    invalidate();
                    return;
                }
                icon.setImageResource(hub ? R.drawable.vibe_ic_campaign : R.drawable.vibe_ic_add);
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

            // Stack label (folder emoji or initials).
            if (item.kind == RAIL_STACK && item.label != null) {
                boolean sel = item.dialogId == selectedId;
                labelPaint.setColor(sel ? 0xFFFFFFFF : text);
                boolean emoji = item.label.length() > 0 && !Character.isLetterOrDigit(item.label.codePointAt(0));
                labelPaint.setTextSize(dp(emoji ? 22 : 16));
                float tw = labelPaint.measureText(item.label);
                Paint.FontMetrics lfm = labelPaint.getFontMetrics();
                canvas.drawText(item.label, cx - tw / 2f, cy - (lfm.ascent + lfm.descent) / 2f, labelPaint);
            }

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
        private TLObject headerPeer;

        PaneHeader(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(16), dp(16), dp(8));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(32));
            avatar.setOnClickListener(v -> {
                if (headerPeer != null) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    VibeProfileSheet.show(fragment, headerPeer);
                }
            });
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
                headerPeer = item.peer;
                avatar.setRoundRadius(dp(32));
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
            avatar.setRoundRadius(dp(12));
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
                avatar.setRoundRadius(dp(12));
                avatar.setForUserOrChat(item.peer, avatarDrawable);
            } else {
                avatar.setVisibility(GONE);
                icon.setVisibility(VISIBLE);
                icon.setText(item.icon);
                icon.setTextColor(gray);
            }
            boolean unread = item.unread > 0;
            if (item.peer == null && "#".equals(item.icon) && item.title != null) {
                // Discord style "# | chat": a thin bar between the hash and the name.
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("|  ");
                sb.setSpan(new android.text.style.ForegroundColorSpan((gray & 0x00FFFFFF) | 0x99000000), 0, 1, 0);
                sb.append(item.title);
                title.setText(sb);
            } else {
                title.setText(item.title);
            }
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
