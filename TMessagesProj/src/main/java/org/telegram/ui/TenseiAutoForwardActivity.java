package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TenseiAutoForward;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TenseiPillSwitch;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * TenseiGram: Auto-Forward settings screen.
 *
 * Lets the user:
 *  - Toggle auto-forward on/off
 *  - Pick source chats (multiple)
 *  - Pick a destination chat
 *  - Toggle media / text forwarding
 *  - Toggle silent mode
 *  - Toggle remove forward header
 */
public class TenseiAutoForwardActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private TenseiPillSwitch enabledSwitch;
    private TenseiPillSwitch mediaSwitch;
    private TenseiPillSwitch textSwitch;
    private TenseiPillSwitch silentSwitch;
    private TenseiPillSwitch removeHeaderSwitch;
    private TextView sourceValue;
    private TextView destValue;
    private LinearLayout optionsContainer;

    private boolean lastIsDark;
    private boolean themeInit;

    private static final int SELECT_SOURCE = 1001;
    private static final int SELECT_DEST = 1002;
    private int pendingSelectMode;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            checkThemeUpdate();
        }
    }

    private void checkThemeUpdate() {
        boolean currentDark = TenseiTheme.isDark(getContext());
        if (!themeInit) {
            themeInit = true;
            lastIsDark = currentDark;
            return;
        }
        if (lastIsDark != currentDark) {
            lastIsDark = currentDark;
            if (fragmentView != null && fragmentView.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) fragmentView.getParent();
                int index = parent.indexOfChild(fragmentView);
                ViewGroup.LayoutParams lp = fragmentView.getLayoutParams();
                parent.removeView(fragmentView);
                View newView = createView(getContext());
                parent.addView(newView, index, lp);
                checkSystemBarColors();
            }
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString("TenseiAutoForward", R.string.TenseiAutoForward));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final boolean dark = TenseiTheme.isDark(context);
        lastIsDark = dark;
        themeInit = true;
        final int colorBg = TenseiTheme.getBgColor(context);
        final int colorCard = TenseiTheme.getCardColor(context);
        final int colorTitle = TenseiTheme.getTitleColor(context);
        final int colorSub = TenseiTheme.getSubtitleColor(context);
        final int colorChevron = TenseiTheme.getChevronColor(context);

        TenseiAutoForward af = TenseiAutoForward.getInstance(currentAccount);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(colorBg);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setFillViewport(true);
        root.addView(scrollView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12),
                AndroidUtilities.dp(14), AndroidUtilities.dp(20));
        scrollView.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        // Header
        content.addView(makeHeaderCard(context,
                LocaleController.getString("TenseiAutoForward", R.string.TenseiAutoForward),
                LocaleController.getString("TenseiAutoForwardInfo", R.string.TenseiAutoForwardInfo),
                colorTitle, colorSub));

        // Main card
        LinearLayout mainCard = makeCard(context, colorCard, dark);
        LinearLayout.LayoutParams mainLp = LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        mainLp.topMargin = AndroidUtilities.dp(10);
        content.addView(mainCard, mainLp);

        // Enable toggle
        enabledSwitch = addToggleRow(context, mainCard,
                LocaleController.getString("TenseiAutoForwardEnabled", R.string.TenseiAutoForwardEnabled),
                LocaleController.getString("TenseiAutoForwardEnabledInfo", R.string.TenseiAutoForwardEnabledInfo),
                af.enabled, colorTitle, colorSub,
                checked -> {
                    af.enabled = checked;
                    af.save();
                    updateOptionsVisibility();
                });

        // Options container (shown only when enabled)
        optionsContainer = new LinearLayout(context);
        optionsContainer.setOrientation(LinearLayout.VERTICAL);
        mainCard.addView(optionsContainer, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Source chats row
        View sourceRow = makeClickRow(context,
                LocaleController.getString("TenseiAutoForwardSources", R.string.TenseiAutoForwardSources),
                LocaleController.getString("TenseiAutoForwardSourcesInfo", R.string.TenseiAutoForwardSourcesInfo),
                colorTitle, colorSub, colorChevron,
                v -> openSourcePicker());
        optionsContainer.addView(sourceRow, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Source value display
        sourceValue = new TextView(context);
        sourceValue.setTextSize(13);
        sourceValue.setTextColor(0xFF2AABEE);
        sourceValue.setPadding(AndroidUtilities.dp(16), 0,
                AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        optionsContainer.addView(sourceValue, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Destination row
        View destRow = makeClickRow(context,
                LocaleController.getString("TenseiAutoForwardDest", R.string.TenseiAutoForwardDest),
                LocaleController.getString("TenseiAutoForwardDestInfo", R.string.TenseiAutoForwardDestInfo),
                colorTitle, colorSub, colorChevron,
                v -> openDestPicker());
        optionsContainer.addView(destRow, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Dest value display
        destValue = new TextView(context);
        destValue.setTextSize(13);
        destValue.setTextColor(0xFF2AABEE);
        destValue.setPadding(AndroidUtilities.dp(16), 0,
                AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        optionsContainer.addView(destValue, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Forward media toggle
        mediaSwitch = addToggleRow(context, optionsContainer,
                LocaleController.getString("TenseiAutoForwardMedia", R.string.TenseiAutoForwardMedia),
                LocaleController.getString("TenseiAutoForwardMediaInfo", R.string.TenseiAutoForwardMediaInfo),
                af.forwardMedia, colorTitle, colorSub,
                checked -> {
                    af.forwardMedia = checked;
                    af.save();
                });

        // Forward text toggle
        textSwitch = addToggleRow(context, optionsContainer,
                LocaleController.getString("TenseiAutoForwardText", R.string.TenseiAutoForwardText),
                LocaleController.getString("TenseiAutoForwardTextInfo", R.string.TenseiAutoForwardTextInfo),
                af.forwardText, colorTitle, colorSub,
                checked -> {
                    af.forwardText = checked;
                    af.save();
                });

        // Silent toggle
        silentSwitch = addToggleRow(context, optionsContainer,
                LocaleController.getString("TenseiAutoForwardSilent", R.string.TenseiAutoForwardSilent),
                LocaleController.getString("TenseiAutoForwardSilentInfo", R.string.TenseiAutoForwardSilentInfo),
                af.silent, colorTitle, colorSub,
                checked -> {
                    af.silent = checked;
                    af.save();
                });

        // Remove header toggle
        removeHeaderSwitch = addToggleRow(context, optionsContainer,
                LocaleController.getString("TenseiAutoForwardRemoveHeader", R.string.TenseiAutoForwardRemoveHeader),
                LocaleController.getString("TenseiAutoForwardRemoveHeaderInfo", R.string.TenseiAutoForwardRemoveHeaderInfo),
                af.removeHeader, colorTitle, colorSub,
                checked -> {
                    af.removeHeader = checked;
                    af.save();
                });

        updateLabels();
        updateOptionsVisibility();

        fragmentView = root;
        return root;
    }

    private void updateLabels() {
        TenseiAutoForward af = TenseiAutoForward.getInstance(currentAccount);

        // Source label
        int count = af.sourceCount();
        if (count == 0) {
            sourceValue.setText(LocaleController.getString(
                    "TenseiAutoForwardNoSources", R.string.TenseiAutoForwardNoSources));
        } else {
            HashSet<Long> ids = af.getSourceIds();
            StringBuilder sb = new StringBuilder();
            for (Long id : ids) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(TenseiAutoForward.getDialogTitle(currentAccount, id));
            }
            sourceValue.setText(sb.toString());
        }

        // Dest label
        if (af.destDialogId == 0) {
            destValue.setText(LocaleController.getString(
                    "TenseiAutoForwardNoDest", R.string.TenseiAutoForwardNoDest));
        } else {
            destValue.setText(TenseiAutoForward.getDialogTitle(currentAccount, af.destDialogId));
        }
    }

    private void updateOptionsVisibility() {
        TenseiAutoForward af = TenseiAutoForward.getInstance(currentAccount);
        if (optionsContainer != null) {
            optionsContainer.setVisibility(af.enabled ? View.VISIBLE : View.GONE);
        }
    }

    // ── Chat pickers ──────────────────────────────────────────────────────

    private void openSourcePicker() {
        Context ctx = getParentActivity();
        if (ctx == null) ctx = getContext();
        if (ctx == null) return;

        TenseiAutoForward af = TenseiAutoForward.getInstance(currentAccount);

        // Show options: Add source / Remove all
        String[] options;
        if (af.sourceCount() > 0) {
            options = new String[]{
                    LocaleController.getString("TenseiAutoForwardAddSource", R.string.TenseiAutoForwardAddSource),
                    LocaleController.getString("TenseiAutoForwardRemoveAll", R.string.TenseiAutoForwardRemoveAll)
            };
        } else {
            options = new String[]{
                    LocaleController.getString("TenseiAutoForwardAddSource", R.string.TenseiAutoForwardAddSource)
            };
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(LocaleController.getString("TenseiAutoForwardSources", R.string.TenseiAutoForwardSources));
        builder.setItems(options, (di, which) -> {
            if (which == 0) {
                // Add source: open dialog picker
                pendingSelectMode = SELECT_SOURCE;
                Bundle args = new Bundle();
                args.putBoolean("onlySelect", true);
                args.putBoolean("allowGroups", true);
                args.putBoolean("allowChannels", true);
                args.putBoolean("allowUsers", true);
                args.putBoolean("allowBots", true);
                DialogsActivity dialogsActivity = new DialogsActivity(args);
                dialogsActivity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
                    if (dids != null && !dids.isEmpty()) {
                        for (MessagesStorage.TopicKey key : dids) {
                            af.addSource(key.dialogId);
                        }
                        updateLabels();
                    }
                    fragment.finishFragment();
                    return true;
                });
                presentFragment(dialogsActivity);
            } else if (which == 1) {
                // Remove all
                af.clearSources();
                updateLabels();
            }
        });
        showDialog(builder.create());
    }

    private void openDestPicker() {
        pendingSelectMode = SELECT_DEST;
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("allowGroups", true);
        args.putBoolean("allowChannels", true);
        args.putBoolean("allowUsers", true);
        args.putBoolean("allowBots", true);
        DialogsActivity dialogsActivity = new DialogsActivity(args);
        dialogsActivity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            TenseiAutoForward af = TenseiAutoForward.getInstance(currentAccount);
            if (dids != null && !dids.isEmpty()) {
                af.destDialogId = dids.get(0).dialogId;
                af.save();
                updateLabels();
            }
            fragment.finishFragment();
            return true;
        });
        presentFragment(dialogsActivity);
    }

    // ── UI helpers (same style as TenseiSettingsActivity) ──────────────────

    private FrameLayout makeHeaderCard(Context context, String title, String subtitle,
                                       int colorTitle, int colorSub) {
        final int headerTitleColor = 0xFF1C1C1E;
        final int headerSubColor = 0xFF636366;
        FrameLayout card = new FrameLayout(context);
        card.setBackground(new TenseiHeaderDrawable());

        LinearLayout inner = new LinearLayout(context);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(16),
                AndroidUtilities.dp(18), AndroidUtilities.dp(16));
        card.addView(inner, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(19);
        tvTitle.setTypeface(AndroidUtilities.bold());
        tvTitle.setTextColor(headerTitleColor);
        inner.addView(tvTitle, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView tvSub = new TextView(context);
        tvSub.setText(subtitle);
        tvSub.setTextSize(13.5f);
        tvSub.setTextColor(headerSubColor);
        inner.addView(tvSub, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        return card;
    }

    private LinearLayout makeCard(Context context, int colorCard, boolean dark) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorCard);
        bg.setCornerRadius(AndroidUtilities.dp(20));
        if (dark) {
            bg.setStroke(AndroidUtilities.dp(1), 0xFF242C38);
        }
        card.setBackground(bg);
        return card;
    }

    private interface OnToggle {
        void onToggle(boolean checked);
    }

    private TenseiPillSwitch addToggleRow(Context context, LinearLayout card, String title,
                                          String subtitle, boolean checked, int colorTitle,
                                          int colorSub, OnToggle listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13),
                AndroidUtilities.dp(16), AndroidUtilities.dp(13));
        row.setClickable(true);
        row.setFocusable(true);
        card.addView(row, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = LayoutHelper.createLinear(0,
                LayoutHelper.WRAP_CONTENT, 1f);
        textsLp.rightMargin = AndroidUtilities.dp(12);
        row.addView(texts, textsLp);

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(16.5f);
        tvTitle.setTypeface(AndroidUtilities.bold());
        tvTitle.setTextColor(colorTitle);
        texts.addView(tvTitle);

        TextView tvSub = new TextView(context);
        tvSub.setText(subtitle);
        tvSub.setTextSize(13.2f);
        tvSub.setTextColor(colorSub);
        texts.addView(tvSub, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        TenseiPillSwitch sw = new TenseiPillSwitch(context);
        sw.setChecked(checked, false);
        row.addView(sw, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        sw.setOnCheckedChangeListener((view, isChecked) -> {
            if (listener != null) {
                listener.onToggle(isChecked);
            }
        });
        row.setOnClickListener(v -> sw.performClick());

        return sw;
    }

    private View makeClickRow(Context context, String title, String subtitle,
                              int colorTitle, int colorSub, int colorChevron,
                              View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13),
                AndroidUtilities.dp(16), AndroidUtilities.dp(13));
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setClickable(true);
        row.setFocusable(true);
        if (onClick != null) {
            row.setOnClickListener(onClick);
        }

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = LayoutHelper.createLinear(0,
                LayoutHelper.WRAP_CONTENT, 1f);
        textsLp.rightMargin = AndroidUtilities.dp(12);
        row.addView(texts, textsLp);

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(16.5f);
        tvTitle.setTypeface(AndroidUtilities.bold());
        tvTitle.setTextColor(colorTitle);
        texts.addView(tvTitle);

        TextView tvSub = new TextView(context);
        tvSub.setText(subtitle);
        tvSub.setTextSize(13.2f);
        tvSub.setTextColor(colorSub);
        texts.addView(tvSub, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        ImageView chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.ic_tensei_chevron);
        chevron.setColorFilter(colorChevron);
        chevron.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(chevron, LayoutHelper.createLinear(14, 22));

        return row;
    }

    @Override
    public void onResume() {
        super.onResume();
        TenseiAutoForward.getInstance(currentAccount).load();
        updateLabels();
        updateOptionsVisibility();
        checkThemeUpdate();
    }
}
