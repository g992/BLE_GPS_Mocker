package com.g992.blegpsmocker.ui;

import android.location.Location;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.g992.blegpsmocker.R;

import java.util.function.Supplier;

/**
 * Handles status and telemetry rendering to keep {@link com.g992.blegpsmocker.MainActivity} slim.
 */
public class StatusUiController {

    private final AppCompatActivity activity;
    private final Handler uiHandler;
    private final TextView connectionBadge;
    private final TextView dataAgeBadge;
    private final TextView locationText;
    private final TextView satellitesBadge;
    private final TextView additionalInfoText;
    private final TextView mockLocationStatusText;
    @Nullable
    private Runnable ticker;

    public StatusUiController(
            @NonNull AppCompatActivity activity,
            @NonNull TextView connectionBadge,
            @NonNull TextView dataAgeBadge,
            @NonNull TextView locationText,
            @NonNull TextView satellitesBadge,
            @NonNull TextView additionalInfoText,
            @NonNull TextView mockLocationStatusText
    ) {
        this.activity = activity;
        this.connectionBadge = connectionBadge;
        this.dataAgeBadge = dataAgeBadge;
        this.locationText = locationText;
        this.satellitesBadge = satellitesBadge;
        this.additionalInfoText = additionalInfoText;
        this.mockLocationStatusText = mockLocationStatusText;
        this.uiHandler = new Handler(activity.getMainLooper());
    }

    public void onConnectionChanged(boolean connected) {
        uiHandler.post(
                () -> {
                    String connectionValue =
                            activity.getString(
                                    connected
                                            ? R.string.connection_status_connected
                                            : R.string.connection_status_disconnected
                            );
                    connectionBadge.setText(connectionValue);

                    if (connected) {
                        applyBadgeStyle(connectionBadge, R.color.chip_success, R.color.chip_text_dark);
                    } else {
                        applyBadgeStyle(connectionBadge, R.color.chip_error, R.color.chip_text_light);
                        applyBadgeStyle(dataAgeBadge, R.color.chip_neutral, R.color.chip_text_light);
                        dataAgeBadge.setText(activity.getString(R.string.unknown));
                        resetLocationUi();
                    }
                });
    }

    public void updateLocationInfo(
            @Nullable Location location,
            int satellites,
            int strongSatellites,
            int mediumSatellites,
            int weakSatellites
    ) {
        if (location == null) {
            return;
        }
        uiHandler.post(
                () -> {
                    StringBuilder builder = new StringBuilder();
                    builder.append(
                            String.format(
                                    activity.getString(R.string.location_status),
                                    String.format(
                                            activity.getString(R.string.location_format),
                                            location.getLatitude(),
                                            location.getLongitude()
                                    )
                            )
                    );
                    if (location.hasAltitude()) {
                        builder.append(
                                String.format(
                                        activity.getString(R.string.altitude_format),
                                        location.getAltitude()
                                )
                        );
                    }
                    if (location.hasAccuracy()) {
                        builder.append(
                                String.format(
                                        activity.getString(R.string.location_accuracy_format),
                                        location.getAccuracy()
                                )
                        );
                    }
                    locationText.setText(builder.toString());

                    satellitesBadge.setText(
                            buildSatelliteBadgeText(
                                    satellites,
                                    strongSatellites,
                                    mediumSatellites,
                                    weakSatellites
                            )
                    );
                    if (satellites > 0) {
                        applyBadgeStyle(satellitesBadge, R.color.chip_success, R.color.chip_text_dark);
                    } else {
                        applyBadgeStyle(satellitesBadge, R.color.chip_warning, R.color.chip_text_dark);
                    }

                    StringBuilder infoBuilder = new StringBuilder();
                    if (location.hasSpeed()) {
                        infoBuilder.append(
                                String.format(
                                        activity.getString(R.string.movement_speed),
                                        String.format(
                                                activity.getString(R.string.speed_format),
                                                location.getSpeed()
                                        )
                                )
                        );
                    }
                    if (location.hasBearing()) {
                        if (infoBuilder.length() > 0) {
                            infoBuilder.append("  ");
                        }
                        infoBuilder.append(
                                String.format(
                                        activity.getString(R.string.movement_bearing),
                                        String.format(
                                                activity.getString(R.string.bearing_format),
                                                location.getBearing()
                                        )
                                )
                        );
                    }
                    if (infoBuilder.length() > 0) {
                        additionalInfoText.setText(infoBuilder.toString());
                    } else {
                        additionalInfoText.setText(activity.getString(R.string.additional_info_unknown));
                    }
                });
    }

    public void updateMockLocationStatus(@Nullable String message) {
        uiHandler.post(
                () -> {
                    if (mockLocationStatusText != null) {
                        if (message == null || message.isEmpty()) {
                            mockLocationStatusText.setVisibility(View.GONE);
                        } else {
                            mockLocationStatusText.setVisibility(View.VISIBLE);
                            mockLocationStatusText.setText(message);
                        }
                    }
                });
    }

    public void startDynamicUpdates(@NonNull Supplier<Long> lastUpdateSupplier) {
        stopDynamicUpdates();
        ticker =
                new Runnable() {
                    @Override
                    public void run() {
                        updateDynamicInfo(lastUpdateSupplier);
                        uiHandler.postDelayed(this, 1000);
                    }
                };
        uiHandler.postDelayed(ticker, 1000);
    }

    public void stopDynamicUpdates() {
        if (ticker != null) {
            uiHandler.removeCallbacks(ticker);
            ticker = null;
        }
    }

    private void updateDynamicInfo(Supplier<Long> lastUpdateSupplier) {
        long lastUpdate = lastUpdateSupplier.get();
        if (lastUpdate > 0) {
            long ageSeconds = (System.currentTimeMillis() - lastUpdate) / 1000;
            uiHandler.post(
                    () -> {
                        String ageDisplay;
                        int backgroundRes;
                        if (ageSeconds < 60) {
                            ageDisplay =
                                    String.format(
                                            activity.getString(R.string.data_age_format_s),
                                            ageSeconds
                                    );
                            backgroundRes = R.color.chip_success;
                        } else {
                            ageDisplay =
                                    String.format(
                                            activity.getString(R.string.data_age_format_ms),
                                            ageSeconds / 60,
                                            ageSeconds % 60
                                    );
                            backgroundRes = R.color.chip_warning;
                        }
                        dataAgeBadge.setText(ageDisplay);
                        applyBadgeStyle(dataAgeBadge, backgroundRes, R.color.chip_text_dark);
                    });
            return;
        }
        uiHandler.post(
                () -> {
                    dataAgeBadge.setText(activity.getString(R.string.unknown));
                    applyBadgeStyle(dataAgeBadge, R.color.chip_neutral, R.color.chip_text_light);
                });
    }

    private void resetLocationUi() {
        locationText.setText(activity.getString(R.string.location_unknown));
        satellitesBadge.setText(activity.getString(R.string.unknown));
        additionalInfoText.setText(activity.getString(R.string.additional_info_unknown));
        applyBadgeStyle(satellitesBadge, R.color.chip_neutral, R.color.chip_text_light);
    }

    private CharSequence buildSatelliteBadgeText(
            int total,
            int strongSatellites,
            int mediumSatellites,
            int weakSatellites
    ) {
        int strong = Math.max(strongSatellites, 0);
        int medium = Math.max(mediumSatellites, 0);
        int weak = Math.max(weakSatellites, 0);
        int breakdownSum = strong + medium + weak;
        int effectiveTotal = total > 0 ? total : breakdownSum;
        if (effectiveTotal < 0) {
            effectiveTotal = 0;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(String.valueOf(effectiveTotal));
        if (breakdownSum > 0) {
            builder.append(' ');
            builder.append('(');
            int strongStart = builder.length();
            builder.append(String.valueOf(strong));
            builder.setSpan(
                    new ForegroundColorSpan(ContextCompat.getColor(activity, R.color.chip_success)),
                    strongStart,
                    builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            builder.append('/');
            int mediumStart = builder.length();
            builder.append(String.valueOf(medium));
            builder.setSpan(
                    new ForegroundColorSpan(ContextCompat.getColor(activity, R.color.chip_warning)),
                    mediumStart,
                    builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            builder.append('/');
            int weakStart = builder.length();
            builder.append(String.valueOf(weak));
            builder.setSpan(
                    new ForegroundColorSpan(ContextCompat.getColor(activity, R.color.chip_error)),
                    weakStart,
                    builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            builder.append(')');
        }
        return builder;
    }

    private void applyBadgeStyle(TextView badge, @ColorRes int backgroundColorRes, @ColorRes int textColorRes) {
        if (badge.getBackground() != null) {
            DrawableCompat.setTint(
                    DrawableCompat.wrap(badge.getBackground().mutate()),
                    ContextCompat.getColor(activity, backgroundColorRes)
            );
        } else {
            badge.setBackgroundColor(ContextCompat.getColor(activity, backgroundColorRes));
        }
        badge.setTextColor(ContextCompat.getColor(activity, textColorRes));
    }
}
