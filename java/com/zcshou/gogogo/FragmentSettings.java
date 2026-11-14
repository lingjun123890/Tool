package com.zcshou.gogogo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.elvishew.xlog.XLog;
import com.zcshou.utils.GoUtils;

import java.util.Objects;

public class FragmentSettings extends PreferenceFragmentCompat {

    // Set a non-empty decimal EditTextPreference
    private void setupDecimalEditTextPreference(EditTextPreference preference) {
        if (preference != null) {
            preference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) EditTextPreference::getText);
            preference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_CLASS_NUMBER);
                editText.setSelection(editText.length());
            });
            preference.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue.toString().trim().isEmpty()) {
                    GoUtils.DisplayToast(this.getContext(), getResources().getString(R.string.app_error_input_null));
                    return false;
                }
                return true;
            });
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences_main);

        ListPreference pfJoystick = findPreference("setting_joystick_type");
        if (pfJoystick != null) {
            // 使用自定义 SummaryProvider
            pfJoystick.setSummaryProvider((Preference.SummaryProvider<ListPreference>) preference -> Objects.requireNonNull(preference.getEntry()));
            pfJoystick.setOnPreferenceChangeListener((preference, newValue) -> !newValue.toString().trim().isEmpty());
        }

        EditTextPreference pfWalk = findPreference("setting_walk");
        setupDecimalEditTextPreference(pfWalk);

        EditTextPreference pfRun = findPreference("setting_run");
        setupDecimalEditTextPreference(pfRun);

        EditTextPreference pfBike = findPreference("setting_bike");
        setupDecimalEditTextPreference(pfBike);

        EditTextPreference pfAltitude = findPreference("setting_altitude");
        setupDecimalEditTextPreference(pfAltitude);

        EditTextPreference pfLatOffset = findPreference("setting_lat_max_offset");
        setupDecimalEditTextPreference(pfLatOffset);

        EditTextPreference pfLonOffset = findPreference("setting_lon_max_offset");
        setupDecimalEditTextPreference(pfLonOffset);

        SwitchPreferenceCompat pLog = findPreference("setting_log_off");
        if (pLog != null) {
            pLog.setOnPreferenceChangeListener((preference, newValue) -> {
                if(((SwitchPreferenceCompat) preference).isChecked() != (Boolean) newValue) {
                    XLog.d(preference.getKey() + newValue);

                    if (Boolean.parseBoolean(newValue.toString())) {
                        XLog.d("on");
                    } else {
                        XLog.d("off");
                    }
                    return true;
                } else {
                    return false;
                }
            });
        }

        EditTextPreference pfPosHisValid = findPreference("setting_history_expiration");
        setupDecimalEditTextPreference(pfPosHisValid);

        // 开源版：地图 Key 由用户自行填写，修改后立即注入高德 SDK
        EditTextPreference pfMapKey = findPreference("setting_amap_key");
        if (pfMapKey != null) {
            pfMapKey.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference ->
                    TextUtils.isEmpty(preference.getText())
                            ? getResources().getString(R.string.amap_key_hint)
                            : preference.getText());
            pfMapKey.setOnPreferenceChangeListener((preference, newValue) -> {
                if (TextUtils.isEmpty(newValue.toString().trim())) {
                    GoUtils.DisplayToast(FragmentSettings.this.getContext(),
                            getResources().getString(R.string.amap_key_empty));
                    return false;
                }
                GoUtils.applyAmapApiKey(newValue.toString());
                GoUtils.DisplayToast(FragmentSettings.this.getContext(),
                        getResources().getString(R.string.amap_key_saved));
                return true;
            });
        }

        Preference pfMapKeyHelp = findPreference("setting_amap_key_help");
        if (pfMapKeyHelp != null) {
            pfMapKeyHelp.setOnPreferenceClickListener(preference -> {
                GoUtils.openAmapKeyApplyPage(FragmentSettings.this.getContext());
                return true;
            });
        }

        // 关于：原作者与本项目开源地址，以及 GPL v3 协议说明
        Preference pfAboutSource = findPreference("setting_about_source");
        if (pfAboutSource != null) {
            pfAboutSource.setOnPreferenceClickListener(preference -> {
                new AlertDialog.Builder(FragmentSettings.this.requireContext())
                        .setTitle(R.string.about_source_title)
                        .setMessage(R.string.about_source_content)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.about_source_open_origin, (dialog, which) ->
                                openUrl("https://github.com/ZCShou/GoGoGo"))
                        .setPositiveButton(R.string.about_source_open_mine, (dialog, which) ->
                                openUrl("https://github.com/lingjun123890/Tool"))
                        .show();
                return true;
            });
        }

        // 设置版本号
        String verName;
        verName = GoUtils.getVersionName(FragmentSettings.this.getContext());
        Preference pfVersion = findPreference("setting_version");
        if (pfVersion != null) {
            pfVersion.setSummary(verName);
        }
    }

    /* 打开浏览器访问开源地址，失败时给出提示 */
    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            GoUtils.DisplayToast(FragmentSettings.this.getContext(), "无法打开浏览器：" + url);
        }
    }
}