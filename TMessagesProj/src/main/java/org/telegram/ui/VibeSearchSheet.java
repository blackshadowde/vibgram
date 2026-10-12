package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
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
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Vibgram: "Find or start a conversation". Searches your chats and all of
 * Telegram (people, bots, channels, groups). Tapping a result you already
 * have opens it; anything new opens the profile card to start or join.
 */
@SuppressLint("ViewConstructor")
public class VibeSearchSheet extends BottomSheet {

    private final BaseFragment fragment;
    private final int account;
    private final ArrayList<Object> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private final TextView emptyText;
    private int reqId;
    private String lastQuery = "";

    private final Runnable searchRunnable = this::runGlobalSearch;

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        new VibeSearchSheet(fragment).show();
    }

    private VibeSearchSheet(BaseFragment fragment) {
        super(fragment.getParentActivity(), true);
        this.fragment = fragment;
        this.account = fragment.getCurrentAccount();
        Context context = fragment.getParentActivity();
        setApplyTopPadding(false);
        setBackgroundColor(VibeLayout.bg());

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(VibeLayout.divider());
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        root.addView(handle, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 8));

        TextView title = new TextView(context);
        title.setText("Find or start a conversation");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(VibeLayout.boldFont());
        title.setTextColor(VibeLayout.text());
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 10));

        EditText field = new EditText(context);
        field.setHint("Search names or @username");
        field.setHintTextColor(VibeLayout.text2());
        field.setTextColor(VibeLayout.text());
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(VibeLayout.rail());
        fieldBg.setCornerRadius(dp(12));
        field.setBackground(fieldBg);
        root.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 16, 0, 16, 8));

        FrameLayout listFrame = new FrameLayout(context);
        RecyclerListView list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        list.setAdapter(adapter);
        list.setVerticalScrollBarEnabled(false);
        list.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size() || !(rows.get(position) instanceof Result)) {
                return;
            }
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            Result r = (Result) rows.get(position);
            AndroidUtilities.hideKeyboard(field);
            if (r.existing) {
                dismiss();
                Bundle args = new Bundle();
                if (DialogObject.isEncryptedDialog(r.dialogId)) {
                    args.putInt("enc_id", DialogObject.getEncryptedChatId(r.dialogId));
                } else if (r.dialogId > 0) {
                    args.putLong("user_id", r.dialogId);
                } else {
                    args.putLong("chat_id", -r.dialogId);
                }
                fragment.presentFragment(new ChatActivity(args));
            } else {
                dismiss();
                VibeProfileSheet.show(fragment, r.peer);
            }
        });
        listFrame.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        emptyText = new TextView(context);
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyText.setTextColor(VibeLayout.text2());
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setText("Type a name or @username to find people, bots, channels and groups.");
        emptyText.setPadding(dp(32), 0, dp(32), 0);
        listFrame.addView(emptyText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 40, 0, 0));
        int listHeight = (int) (AndroidUtilities.displaySize.y * 0.62f);
        root.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onQuery(s.toString());
            }
        });
        setCustomView(root);
        AndroidUtilities.runOnUIThread(() -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        }, 250);
    }

    private void onQuery(String text) {
        String q = text.trim();
        if (q.startsWith("@")) {
            q = q.substring(1);
        }
        lastQuery = q;
        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        if (reqId != 0) {
            ConnectionsManager.getInstance(account).cancelRequest(reqId, true);
            reqId = 0;
        }
        rows.clear();
        if (q.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            adapter.notifyDataSetChanged();
            return;
        }
        emptyText.setVisibility(View.GONE);
        searchLocal(q.toLowerCase());
        adapter.notifyDataSetChanged();
        if (q.length() >= 3) {
            AndroidUtilities.runOnUIThread(searchRunnable, 400);
        }
    }

    private void searchLocal(String q) {
        MessagesController mc = MessagesController.getInstance(account);
        ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
        ArrayList<Result> found = new ArrayList<>();
        for (int i = 0; i < dialogs.size() && found.size() < 30; i++) {
            TLRPC.Dialog d = dialogs.get(i);
            if (d == null || DialogObject.isFolderDialogId(d.id) || DialogObject.isEncryptedDialog(d.id)) {
                continue;
            }
            TLObject peer = d.id > 0 ? mc.getUser(d.id) : mc.getChat(-d.id);
            if (peer == null) {
                continue;
            }
            String name = titleOf(peer);
            String username = usernameOf(peer);
            if (name.toLowerCase().contains(q) || username != null && username.toLowerCase().startsWith(q)) {
                Result r = new Result();
                r.peer = peer;
                r.dialogId = d.id;
                r.existing = true;
                found.add(r);
            }
        }
        if (!found.isEmpty()) {
            rows.add("YOUR CHATS");
            rows.addAll(found);
        }
    }

    private void runGlobalSearch() {
        final String q = lastQuery;
        TLRPC.TL_contacts_search req = new TLRPC.TL_contacts_search();
        req.q = q;
        req.limit = 20;
        reqId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqId = 0;
            if (!q.equals(lastQuery) || !(response instanceof TLRPC.TL_contacts_found)) {
                return;
            }
            TLRPC.TL_contacts_found res = (TLRPC.TL_contacts_found) response;
            MessagesController mc = MessagesController.getInstance(account);
            mc.putUsers(res.users, false);
            mc.putChats(res.chats, false);
            HashSet<Long> already = new HashSet<>();
            for (Object o : rows) {
                if (o instanceof Result) {
                    already.add(((Result) o).dialogId);
                }
            }
            ArrayList<Result> global = new ArrayList<>();
            ArrayList<TLRPC.Peer> peers = new ArrayList<>(res.my_results);
            peers.addAll(res.results);
            for (TLRPC.Peer p : peers) {
                long did = MessageObject.getPeerId(p);
                if (did == 0 || already.contains(did)) {
                    continue;
                }
                TLObject peer = did > 0 ? mc.getUser(did) : mc.getChat(-did);
                if (peer == null) {
                    continue;
                }
                already.add(did);
                Result r = new Result();
                r.peer = peer;
                r.dialogId = did;
                r.existing = mc.dialogs_dict.get(did) != null;
                global.add(r);
            }
            if (!global.isEmpty()) {
                rows.add("ON TELEGRAM");
                rows.addAll(global);
            }
            if (rows.isEmpty()) {
                emptyText.setText("Nothing found for \"" + q + "\"");
                emptyText.setVisibility(View.VISIBLE);
            }
            adapter.notifyDataSetChanged();
        }));
    }

    static String titleOf(TLObject peer) {
        if (peer instanceof TLRPC.User) {
            return UserObject.getUserName((TLRPC.User) peer);
        }
        return peer instanceof TLRPC.Chat && ((TLRPC.Chat) peer).title != null ? ((TLRPC.Chat) peer).title : "";
    }

    static String usernameOf(TLObject peer) {
        if (peer instanceof TLRPC.User) {
            return UserObject.getPublicUsername((TLRPC.User) peer);
        }
        return peer instanceof TLRPC.Chat ? ChatObject.getPublicUsername((TLRPC.Chat) peer) : null;
    }

    private String subtitleOf(TLObject peer) {
        String username = usernameOf(peer);
        String kind;
        if (peer instanceof TLRPC.User) {
            TLRPC.User u = (TLRPC.User) peer;
            kind = u.bot ? "bot" : LocaleController.formatUserStatus(account, u);
        } else {
            TLRPC.Chat c = (TLRPC.Chat) peer;
            kind = ChatObject.isChannelAndNotMegaGroup(c) ? "channel" : "group";
            if (c.participants_count > 0) {
                kind += " · " + LocaleController.formatPluralString(ChatObject.isChannelAndNotMegaGroup(c) ? "Subscribers" : "Members", c.participants_count);
            }
        }
        return TextUtils.isEmpty(username) ? kind : "@" + username + " · " + kind;
    }

    private static class Result {
        TLObject peer;
        long dialogId;
        boolean existing;
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == 1;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof Result ? 1 : 0;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v;
            if (viewType == 0) {
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                tv.setTypeface(VibeLayout.boldFont());
                tv.setTextColor(VibeLayout.text2());
                tv.setPadding(dp(16), dp(14), dp(16), dp(6));
                v = tv;
            } else {
                v = new Row(parent.getContext());
            }
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object o = rows.get(position);
            if (holder.itemView instanceof Row && o instanceof Result) {
                ((Row) holder.itemView).bind((Result) o);
            } else if (holder.itemView instanceof TextView) {
                ((TextView) holder.itemView).setText(String.valueOf(o));
            }
        }
    }

    private class Row extends FrameLayout {
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView name;
        private final TextView sub;

        Row(Context context) {
            super(context);
            setMinimumHeight(dp(60));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(20));
            addView(avatar, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTypeface(VibeLayout.boldFont());
            name.setTextColor(VibeLayout.text());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name);
            sub = new TextView(context);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            sub.setTextColor(VibeLayout.text2());
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(sub);
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 70, 8, 16, 8));
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        }

        void bind(Result r) {
            avatarDrawable.setInfo(account, r.peer);
            avatar.setForUserOrChat(r.peer, avatarDrawable);
            name.setText(titleOf(r.peer));
            sub.setText(subtitleOf(r.peer));
        }
    }
}
