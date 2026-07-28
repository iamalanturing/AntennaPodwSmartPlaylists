package de.danoeh.antennapod.ui.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.appcompat.app.AppCompatActivity;

import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.storage.database.DBReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds one widget instance to a Smart Queue. Everything chosen here is stored per
 * {@code appWidgetId}, so several widgets can point at different queues.
 */
public class SmartQueueWidgetConfigActivity extends AppCompatActivity {
    private static final int[] COLOURS = {
        SmartQueueWidget.DEFAULT_COLOR, 0xff1B4F72, 0xff186A3B, 0xff512E5F, 0xff7B241C, 0xff7E5109};
    private static final int[] COLOUR_LABELS = {
        R.string.smart_queue_widget_colour_default, R.string.smart_queue_widget_colour_blue,
        R.string.smart_queue_widget_colour_green, R.string.smart_queue_widget_colour_purple,
        R.string.smart_queue_widget_colour_red, R.string.smart_queue_widget_colour_orange};

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private final List<SmartPlaylist> playlists = new ArrayList<>();
    private RadioGroup playlistGroup;
    private RadioGroup colourGroup;
    private EditText initialsEdit;
    private boolean initialsEditedByUser = false;
    private boolean settingInitialsProgrammatically = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smart_queue_widget_config);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        Intent resultValue = new Intent();
        resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(Activity.RESULT_CANCELED, resultValue);
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        playlistGroup = findViewById(R.id.playlist_group);
        colourGroup = findViewById(R.id.colour_group);
        initialsEdit = findViewById(R.id.initials_edit);
        Button confirm = findViewById(R.id.butConfirm);
        confirm.setOnClickListener(v -> confirm());
        confirm.setEnabled(false);

        buildColourChoices();
        loadPlaylists(confirm);
    }

    private void buildColourChoices() {
        SharedPreferences prefs = getSharedPreferences(SmartQueueWidget.PREFS_NAME, Context.MODE_PRIVATE);
        int stored = prefs.getInt(SmartQueueWidget.KEY_COLOR + appWidgetId, SmartQueueWidget.DEFAULT_COLOR);
        for (int i = 0; i < COLOURS.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(COLOUR_LABELS[i]);
            button.setTag(COLOURS[i]);
            button.setChecked(COLOURS[i] == stored);
            colourGroup.addView(button);
        }
        if (colourGroup.getCheckedRadioButtonId() == -1 && colourGroup.getChildCount() > 0) {
            ((RadioButton) colourGroup.getChildAt(0)).setChecked(true);
        }
    }

    private void loadPlaylists(Button confirm) {
        new Thread(() -> {
            final List<SmartPlaylist> loaded = DBReader.getSmartPlaylists();
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                playlists.clear();
                playlists.addAll(loaded);
                if (playlists.isEmpty()) {
                    findViewById(R.id.empty_label).setVisibility(View.VISIBLE);
                    return;
                }
                buildPlaylistChoices();
                confirm.setEnabled(true);
            });
        }, "SmartQueueWidgetConfig").start();
    }

    private void buildPlaylistChoices() {
        SharedPreferences prefs = getSharedPreferences(SmartQueueWidget.PREFS_NAME, Context.MODE_PRIVATE);
        long stored = prefs.getLong(SmartQueueWidget.KEY_PLAYLIST_ID + appWidgetId, 0);
        for (SmartPlaylist playlist : playlists) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(playlist.getName());
            button.setTag(playlist.getId());
            button.setChecked(playlist.getId() == stored);
            playlistGroup.addView(button);
        }
        if (playlistGroup.getCheckedRadioButtonId() == -1) {
            ((RadioButton) playlistGroup.getChildAt(0)).setChecked(true);
        }
        playlistGroup.setOnCheckedChangeListener((group, checkedId) -> suggestInitials());

        String storedInitials = prefs.getString(SmartQueueWidget.KEY_INITIALS + appWidgetId, null);
        if (TextUtils.isEmpty(storedInitials)) {
            suggestInitials();
        } else {
            setInitialsText(storedInitials);
            initialsEditedByUser = true;
        }
        initialsEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!settingInitialsProgrammatically) {
                    initialsEditedByUser = true;
                }
            }
        });
    }

    /** Keeps the letters in step with the chosen queue until the user types their own. */
    private void suggestInitials() {
        if (initialsEditedByUser) {
            return;
        }
        SmartPlaylist selected = selectedPlaylist();
        if (selected != null) {
            setInitialsText(SmartQueueWidgetUpdater.deriveInitials(selected.getName()));
        }
    }

    private void setInitialsText(String text) {
        settingInitialsProgrammatically = true;
        initialsEdit.setText(text);
        settingInitialsProgrammatically = false;
    }

    private SmartPlaylist selectedPlaylist() {
        int checkedId = playlistGroup.getCheckedRadioButtonId();
        if (checkedId == -1) {
            return null;
        }
        long playlistId = (long) playlistGroup.findViewById(checkedId).getTag();
        for (SmartPlaylist playlist : playlists) {
            if (playlist.getId() == playlistId) {
                return playlist;
            }
        }
        return null;
    }

    private void confirm() {
        SmartPlaylist selected = selectedPlaylist();
        if (selected == null) {
            return;
        }
        int colour = SmartQueueWidget.DEFAULT_COLOR;
        int checkedColour = colourGroup.getCheckedRadioButtonId();
        if (checkedColour != -1) {
            colour = (int) colourGroup.findViewById(checkedColour).getTag();
        }
        String initials = initialsEdit.getText().toString().trim();
        if (initials.isEmpty()) {
            initials = SmartQueueWidgetUpdater.deriveInitials(selected.getName());
        }

        SharedPreferences.Editor editor =
                getSharedPreferences(SmartQueueWidget.PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putLong(SmartQueueWidget.KEY_PLAYLIST_ID + appWidgetId, selected.getId());
        editor.putInt(SmartQueueWidget.KEY_COLOR + appWidgetId, colour);
        editor.putString(SmartQueueWidget.KEY_INITIALS + appWidgetId, initials);
        editor.apply();

        Intent resultValue = new Intent();
        resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(Activity.RESULT_OK, resultValue);
        finish();
        SmartQueueWidgetWorker.enqueueWork(this);
    }
}
