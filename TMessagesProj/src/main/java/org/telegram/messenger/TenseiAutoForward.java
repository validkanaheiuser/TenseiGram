package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * TenseiGram: Auto-Forward Engine.
 *
 * Monitors incoming messages from user-selected source chats and automatically
 * forwards them to a user-selected destination chat. Runs as a NotificationCenter
 * observer on didReceiveNewMessages.
 *
 * Config is stored in SharedPreferences "tensei_autoforward":
 *   - "enabled"       : boolean master switch
 *   - "source_ids"    : Set<String> of dialog IDs to monitor
 *   - "dest_id"       : long destination dialog ID
 *   - "forward_media" : boolean whether to forward media messages
 *   - "forward_text"  : boolean whether to forward text messages
 *   - "silent"        : boolean send without notification sound
 *   - "remove_header" : boolean strip forward header (send as copy)
 */
public class TenseiAutoForward implements NotificationCenter.NotificationCenterDelegate {

    private static volatile TenseiAutoForward[] instances = new TenseiAutoForward[UserConfig.MAX_ACCOUNT_COUNT];

    public boolean enabled;
    public long destDialogId;
    public boolean forwardMedia = true;
    public boolean forwardText = true;
    public boolean silent = false;
    public boolean removeHeader = false;
    private final HashSet<Long> sourceDialogIds = new HashSet<>();
    private final int currentAccount;
    private boolean registered;

    public static TenseiAutoForward getInstance(int account) {
        TenseiAutoForward local = instances[account];
        if (local == null) {
            synchronized (TenseiAutoForward.class) {
                local = instances[account];
                if (local == null) {
                    local = new TenseiAutoForward(account);
                    instances[account] = local;
                }
            }
        }
        return local;
    }

    private TenseiAutoForward(int account) {
        this.currentAccount = account;
        load();
    }

    // ── Prefs ──────────────────────────────────────────────────────────────

    private SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                "tensei_autoforward_" + currentAccount, 0);
    }

    public void load() {
        SharedPreferences prefs = getPrefs();
        enabled = prefs.getBoolean("enabled", false);
        destDialogId = prefs.getLong("dest_id", 0);
        forwardMedia = prefs.getBoolean("forward_media", true);
        forwardText = prefs.getBoolean("forward_text", true);
        silent = prefs.getBoolean("silent", false);
        removeHeader = prefs.getBoolean("remove_header", false);

        sourceDialogIds.clear();
        Set<String> ids = prefs.getStringSet("source_ids", null);
        if (ids != null) {
            for (String s : ids) {
                try {
                    sourceDialogIds.add(Long.parseLong(s));
                } catch (Throwable ignored) {
                }
            }
        }
        updateRegistration();
    }

    public void save() {
        HashSet<String> ids = new HashSet<>();
        for (Long id : sourceDialogIds) {
            ids.add(String.valueOf(id));
        }
        getPrefs().edit()
                .putBoolean("enabled", enabled)
                .putLong("dest_id", destDialogId)
                .putBoolean("forward_media", forwardMedia)
                .putBoolean("forward_text", forwardText)
                .putBoolean("silent", silent)
                .putBoolean("remove_header", removeHeader)
                .putStringSet("source_ids", ids)
                .apply();
        updateRegistration();
    }

    // ── Source management ──────────────────────────────────────────────────

    public HashSet<Long> getSourceIds() {
        return new HashSet<>(sourceDialogIds);
    }

    public void addSource(long dialogId) {
        sourceDialogIds.add(dialogId);
        save();
    }

    public void removeSource(long dialogId) {
        sourceDialogIds.remove(dialogId);
        save();
    }

    public void clearSources() {
        sourceDialogIds.clear();
        save();
    }

    public boolean hasSource(long dialogId) {
        return sourceDialogIds.contains(dialogId);
    }

    public int sourceCount() {
        return sourceDialogIds.size();
    }

    // ── Observer registration ─────────────────────────────────────────────

    private void updateRegistration() {
        if (enabled && !sourceDialogIds.isEmpty() && destDialogId != 0) {
            register();
        } else {
            unregister();
        }
    }

    private void register() {
        if (registered) return;
        try {
            NotificationCenter.getInstance(currentAccount)
                    .addObserver(this, NotificationCenter.didReceiveNewMessages);
            registered = true;
        } catch (Throwable ignored) {
        }
    }

    private void unregister() {
        if (!registered) return;
        try {
            NotificationCenter.getInstance(currentAccount)
                    .removeObserver(this, NotificationCenter.didReceiveNewMessages);
            registered = false;
        } catch (Throwable ignored) {
        }
    }

    // ── Notification callback ─────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages) return;
        if (!enabled || destDialogId == 0 || sourceDialogIds.isEmpty()) return;

        try {
            long dialogId = (Long) args[0];
            if (!sourceDialogIds.contains(dialogId)) return;

            // Never forward from dest to dest (loop guard)
            if (dialogId == destDialogId) return;

            // Don't forward scheduled messages
            boolean scheduled = (Boolean) args[2];
            if (scheduled) return;

            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            if (messages == null || messages.isEmpty()) return;

            // Don't forward our own outgoing messages (avoid loops)
            for (MessageObject mo : messages) {
                if (mo == null || mo.messageOwner == null) continue;
                if (mo.isOut()) continue;

                // Filter by type
                boolean hasMedia = hasMediaContent(mo);
                boolean hasText = hasTextContent(mo);

                if (hasMedia && !forwardMedia) continue;
                if (!hasMedia && hasText && !forwardText) continue;

                // Forward
                if (removeHeader) {
                    copyAsNew(mo);
                } else {
                    forwardWithHeader(mo);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private boolean hasMediaContent(MessageObject mo) {
        try {
            TLRPC.MessageMedia media = MessageObject.getMedia(mo.messageOwner);
            if (media == null || media instanceof TLRPC.TL_messageMediaEmpty
                    || media instanceof TLRPC.TL_messageMediaWebPage) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasTextContent(MessageObject mo) {
        try {
            if (mo.messageText != null && mo.messageText.length() > 0) return true;
            if (mo.caption != null && mo.caption.length() > 0) return true;
            if (mo.messageOwner != null && mo.messageOwner.message != null
                    && !mo.messageOwner.message.isEmpty()) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ── Forward with header (server forward) ──────────────────────────────

    private void forwardWithHeader(MessageObject mo) {
        try {
            ArrayList<MessageObject> msgs = new ArrayList<>();
            msgs.add(mo);
            SendMessagesHelper.getInstance(currentAccount).sendMessage(
                    msgs, destDialogId, false, false, !silent, 0, 0
            );
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    // ── Copy without header (re-send as new message) ──────────────────────

    private void copyAsNew(MessageObject mo) {
        try {
            SendMessagesHelper helper = SendMessagesHelper.getInstance(currentAccount);
            TLRPC.MessageMedia media = MessageObject.getMedia(mo.messageOwner);

            String caption = null;
            try {
                if (mo.caption != null && mo.caption.length() > 0) {
                    caption = mo.caption.toString();
                }
            } catch (Throwable ignored) {
            }

            ArrayList<TLRPC.MessageEntity> entities = null;
            try {
                entities = mo.messageOwner.entities;
            } catch (Throwable ignored) {
            }

            // Document (video, file, audio, voice)
            if (media instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.TL_messageMediaDocument md = (TLRPC.TL_messageMediaDocument) media;
                if (md.document instanceof TLRPC.TL_document) {
                    TLRPC.TL_document doc = (TLRPC.TL_document) md.document;
                    helper.sendMessage(SendMessagesHelper.SendMessageParams.of(
                            doc, null, null, destDialogId, null, null,
                            caption, entities, null, null,
                            !silent, 0, 0, 0, null, null, false));
                    return;
                }
            }

            // Photo
            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                TLRPC.TL_messageMediaPhoto mp = (TLRPC.TL_messageMediaPhoto) media;
                if (mp.photo instanceof TLRPC.TL_photo) {
                    TLRPC.TL_photo photo = (TLRPC.TL_photo) mp.photo;
                    helper.sendMessage(SendMessagesHelper.SendMessageParams.of(
                            photo, null, destDialogId, null, null,
                            caption, entities, null, null,
                            !silent, 0, 0, 0, null, false));
                    return;
                }
            }

            // Plain text
            String text = null;
            try {
                if (mo.messageText != null && mo.messageText.length() > 0) {
                    text = mo.messageText.toString();
                } else if (mo.messageOwner.message != null) {
                    text = mo.messageOwner.message;
                }
            } catch (Throwable ignored) {
            }
            if (text != null && !text.isEmpty()) {
                helper.sendMessage(SendMessagesHelper.SendMessageParams.of(
                        text, destDialogId, null, null, null, true,
                        entities, null, null, !silent, 0, 0, null, false));
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    // ── Utility: get chat/user name for display ───────────────────────────

    public static String getDialogTitle(int account, long dialogId) {
        try {
            if (DialogObject.isUserDialog(dialogId)) {
                TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
                if (user != null) {
                    return ContactsController.formatName(user.first_name, user.last_name);
                }
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(account)
                        .getChat(-dialogId);
                if (chat != null && !TextUtils.isEmpty(chat.title)) {
                    return chat.title;
                }
            }
        } catch (Throwable ignored) {
        }
        return "ID: " + dialogId;
    }
}
