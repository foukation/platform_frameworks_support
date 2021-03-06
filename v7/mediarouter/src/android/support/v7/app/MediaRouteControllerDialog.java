/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.v7.app;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v4.view.accessibility.AccessibilityEventCompat;
import android.support.v7.graphics.Palette;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.support.v7.mediarouter.R;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class implements the route controller dialog for {@link MediaRouter}.
 * <p>
 * This dialog allows the user to control or disconnect from the currently selected route.
 * </p>
 *
 * @see MediaRouteButton
 * @see MediaRouteActionProvider
 */
public class MediaRouteControllerDialog extends AlertDialog {
    private static final String TAG = "MediaRouteControllerDialog";

    // Time to wait before updating the volume when the user lets go of the seek bar
    // to allow the route provider time to propagate the change and publish a new
    // route descriptor.
    private static final int VOLUME_UPDATE_DELAY_MILLIS = 500;

    private static final int BUTTON_NEUTRAL_RES_ID = android.R.id.button3;
    private static final int BUTTON_DISCONNECT_RES_ID = android.R.id.button2;
    private static final int BUTTON_STOP_RES_ID = android.R.id.button1;

    private final MediaRouter mRouter;
    private final MediaRouterCallback mCallback;
    private final MediaRouter.RouteInfo mRoute;

    private Context mContext;
    private boolean mCreated;
    private boolean mAttachedToWindow;

    private int mDialogContentWidth;

    private View mCustomControlView;

    private Button mDisconnectButton;
    private Button mStopCastingButton;
    private ImageButton mPlayPauseButton;
    private ImageButton mCloseButton;
    private MediaRouteExpandCollapseButton mGroupExpandCollapseButton;

    private FrameLayout mExpandableAreaLayout;
    private LinearLayout mDialogAreaLayout;
    private FrameLayout mDefaultControlLayout;
    private FrameLayout mCustomControlLayout;
    private ImageView mArtView;
    private TextView mTitleView;
    private TextView mSubtitleView;
    private TextView mRouteNameTextView;

    private boolean mVolumeControlEnabled = true;
    // Layout for media controllers including play/pause button and the main volume slider.
    private LinearLayout mMediaMainControlLayout;
    private RelativeLayout mPlaybackControlLayout;
    private LinearLayout mVolumeControlLayout;
    private View mDividerView;

    private ListView mVolumeGroupList;
    private SeekBar mVolumeSlider;
    private VolumeChangeListener mVolumeChangeListener;
    private MediaRouter.RouteInfo mRouteInVolumeSliderTouched;
    private int mVolumeGroupListItemIconSize;
    private int mVolumeGroupListItemHeight;
    private int mVolumeGroupListMaxHeight;
    private final int mVolumeGroupListPaddingTop;
    private Map<MediaRouter.RouteInfo, SeekBar> mVolumeSliderMap;

    private MediaControllerCompat mMediaController;
    private MediaControllerCallback mControllerCallback;
    private PlaybackStateCompat mState;
    private MediaDescriptionCompat mDescription;

    private FetchArtTask mFetchArtTask;
    private Bitmap mArtIconBitmap;
    private Uri mArtIconUri;
    private boolean mIsGroupExpanded;
    private boolean mIsGroupListAnimationNeeded;
    private int mGroupListAnimationDurationMs;

    private final AccessibilityManager mAccessibilityManager;

    public MediaRouteControllerDialog(Context context) {
        this(context, 0);
    }

    public MediaRouteControllerDialog(Context context, int theme) {
        super(MediaRouterThemeHelper.createThemedContext(context, theme), theme);
        mContext = getContext();

        mControllerCallback = new MediaControllerCallback();
        mRouter = MediaRouter.getInstance(mContext);
        mCallback = new MediaRouterCallback();
        mRoute = mRouter.getSelectedRoute();
        setMediaSession(mRouter.getMediaSessionToken());
        mVolumeGroupListPaddingTop = mContext.getResources().getDimensionPixelSize(
                R.dimen.mr_controller_volume_group_list_padding_top);
        mAccessibilityManager =
                (AccessibilityManager) mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    /**
     * Gets the route that this dialog is controlling.
     */
    public MediaRouter.RouteInfo getRoute() {
        return mRoute;
    }

    private MediaRouter.RouteGroup getGroup() {
        if (mRoute instanceof MediaRouter.RouteGroup) {
            return (MediaRouter.RouteGroup) mRoute;
        }
        return null;
    }

    /**
     * Provides the subclass an opportunity to create a view that will replace the default media
     * controls for the currently playing content.
     *
     * @param savedInstanceState The dialog's saved instance state.
     * @return The media control view, or null if none.
     */
    public View onCreateMediaControlView(Bundle savedInstanceState) {
        return null;
    }

    /**
     * Gets the media control view that was created by {@link #onCreateMediaControlView(Bundle)}.
     *
     * @return The media control view, or null if none.
     */
    public View getMediaControlView() {
        return mCustomControlView;
    }

    /**
     * Sets whether to enable the volume slider and volume control using the volume keys
     * when the route supports it.
     * <p>
     * The default value is true.
     * </p>
     */
    public void setVolumeControlEnabled(boolean enable) {
        if (mVolumeControlEnabled != enable) {
            mVolumeControlEnabled = enable;
            if (mCreated) {
                updateVolumeControlLayout();
            }
        }
    }

    /**
     * Returns whether to enable the volume slider and volume control using the volume keys
     * when the route supports it.
     */
    public boolean isVolumeControlEnabled() {
        return mVolumeControlEnabled;
    }

    /**
     * Set the session to use for metadata and transport controls. The dialog
     * will listen to changes on this session and update the UI automatically in
     * response to changes.
     *
     * @param sessionToken The token for the session to use.
     */
    private void setMediaSession(MediaSessionCompat.Token sessionToken) {
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mControllerCallback);
            mMediaController = null;
        }
        if (sessionToken == null) {
            return;
        }
        if (!mAttachedToWindow) {
            return;
        }
        try {
            mMediaController = new MediaControllerCompat(mContext, sessionToken);
        } catch (RemoteException e) {
            Log.e(TAG, "Error creating media controller in setMediaSession.", e);
        }
        if (mMediaController != null) {
            mMediaController.registerCallback(mControllerCallback);
        }
        MediaMetadataCompat metadata = mMediaController == null ? null
                : mMediaController.getMetadata();
        mDescription = metadata == null ? null : metadata.getDescription();
        mState = mMediaController == null ? null : mMediaController.getPlaybackState();
        update();
    }

    /**
     * Gets the session to use for metadata and transport controls.
     *
     * @return The token for the session to use or null if none.
     */
    public MediaSessionCompat.Token getMediaSession() {
        return mMediaController == null ? null : mMediaController.getSessionToken();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        setContentView(R.layout.mr_controller_material_dialog_b);

        // Remove the neutral button.
        findViewById(BUTTON_NEUTRAL_RES_ID).setVisibility(View.GONE);

        ClickListener listener = new ClickListener();

        mExpandableAreaLayout = (FrameLayout) findViewById(R.id.mr_expandable_area);
        mExpandableAreaLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        mDialogAreaLayout = (LinearLayout) findViewById(R.id.mr_dialog_area);
        mDialogAreaLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Eat unhandled touch events.
            }
        });
        int color = MediaRouterThemeHelper.getButtonTextColor(mContext);
        mDisconnectButton = (Button) findViewById(BUTTON_DISCONNECT_RES_ID);
        mDisconnectButton.setText(R.string.mr_controller_disconnect);
        mDisconnectButton.setTextColor(color);
        mDisconnectButton.setOnClickListener(listener);

        mStopCastingButton = (Button) findViewById(BUTTON_STOP_RES_ID);
        mStopCastingButton.setText(R.string.mr_controller_stop);
        mStopCastingButton.setTextColor(color);
        mStopCastingButton.setOnClickListener(listener);

        mRouteNameTextView = (TextView) findViewById(R.id.mr_name);
        mCloseButton = (ImageButton) findViewById(R.id.mr_close);
        mCloseButton.setOnClickListener(listener);
        mCustomControlLayout = (FrameLayout) findViewById(R.id.mr_custom_control);
        mDefaultControlLayout = (FrameLayout) findViewById(R.id.mr_default_control);

        // Start the session activity when a content item (album art, title or subtitle) is clicked.
        View.OnClickListener onClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMediaController != null) {
                    PendingIntent pi = mMediaController.getSessionActivity();
                    if (pi != null) {
                        try {
                            pi.send();
                            dismiss();
                        } catch (PendingIntent.CanceledException e) {
                            Log.e(TAG, pi + " was not sent, it had been canceled.");
                        }
                    }
                }
            }
        };
        mArtView = (ImageView) findViewById(R.id.mr_art);
        mArtView.setOnClickListener(onClickListener);
        findViewById(R.id.mr_control_title_container).setOnClickListener(onClickListener);

        mMediaMainControlLayout = (LinearLayout) findViewById(R.id.mr_media_main_control);
        mDividerView = findViewById(R.id.mr_control_divider);

        mPlaybackControlLayout = (RelativeLayout) findViewById(R.id.mr_playback_control);
        mTitleView = (TextView) findViewById(R.id.mr_control_title);
        mSubtitleView = (TextView) findViewById(R.id.mr_control_subtitle);
        mPlayPauseButton = (ImageButton) findViewById(R.id.mr_control_play_pause);
        mPlayPauseButton.setOnClickListener(listener);

        mVolumeControlLayout = (LinearLayout) findViewById(R.id.mr_volume_control);
        mVolumeControlLayout.setVisibility(View.GONE);
        mVolumeSlider = (SeekBar) findViewById(R.id.mr_volume_slider);
        mVolumeSlider.setTag(mRoute);
        mVolumeChangeListener = new VolumeChangeListener();
        mVolumeSlider.setOnSeekBarChangeListener(mVolumeChangeListener);

        mVolumeGroupList = (ListView) findViewById(R.id.mr_volume_group_list);
        MediaRouterThemeHelper.setMediaControlsBackgroundColor(mContext,
                mMediaMainControlLayout, mVolumeGroupList, getGroup() != null);
        MediaRouterThemeHelper.setVolumeSliderColor(mContext,
                (MediaRouteVolumeSlider) mVolumeSlider, mMediaMainControlLayout);
        mVolumeSliderMap = new HashMap<>();
        mVolumeSliderMap.put(mRoute, mVolumeSlider);

        mGroupExpandCollapseButton =
                (MediaRouteExpandCollapseButton) findViewById(R.id.mr_group_expand_collapse);
        mGroupExpandCollapseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mIsGroupExpanded = !mIsGroupExpanded;
                if (mIsGroupExpanded) {
                    mVolumeGroupList.setVisibility(View.VISIBLE);
                    mVolumeGroupList.setAdapter(
                            new VolumeGroupAdapter(mContext, getGroup().getRoutes()));
                } else {
                    // Request layout to update UI based on {@code mIsGroupExpanded}.
                    mDefaultControlLayout.requestLayout();
                }
                mIsGroupListAnimationNeeded = true;
                updateLayoutHeight();
            }
        });
        mGroupListAnimationDurationMs = mContext.getResources().getInteger(
                R.integer.mr_controller_volume_group_list_animation_duration_ms);

        mCustomControlView = onCreateMediaControlView(savedInstanceState);
        if (mCustomControlView != null) {
            mCustomControlLayout.addView(mCustomControlView);
            mCustomControlLayout.setVisibility(View.VISIBLE);
        }
        mCreated = true;
        updateLayout();
    }

    /**
     * Sets the width of the dialog. Also called when configuration changes.
     */
    void updateLayout() {
        int width = MediaRouteDialogHelper.getDialogWidth(mContext);
        getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);

        View decorView = getWindow().getDecorView();
        mDialogContentWidth = width - decorView.getPaddingLeft() - decorView.getPaddingRight();

        Resources res = mContext.getResources();
        mVolumeGroupListItemIconSize = res.getDimensionPixelSize(
                R.dimen.mr_controller_volume_group_list_item_icon_size);
        mVolumeGroupListItemHeight = res.getDimensionPixelSize(
                R.dimen.mr_controller_volume_group_list_item_height);
        mVolumeGroupListMaxHeight = res.getDimensionPixelSize(
                R.dimen.mr_controller_volume_group_list_max_height);

        // Ensure the mArtView is updated.
        mArtIconBitmap = null;
        mArtIconUri = null;
        update();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttachedToWindow = true;

        mRouter.addCallback(MediaRouteSelector.EMPTY, mCallback,
                MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS);
        setMediaSession(mRouter.getMediaSessionToken());
    }

    @Override
    public void onDetachedFromWindow() {
        mRouter.removeCallback(mCallback);
        setMediaSession(null);
        mAttachedToWindow = false;
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mRoute.requestUpdateVolume(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ? -1 : 1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void update() {
        if (!mRoute.isSelected() || mRoute.isDefault()) {
            dismiss();
            return;
        }
        if (!mCreated) {
            return;
        }

        mRouteNameTextView.setText(mRoute.getName());
        mDisconnectButton.setVisibility(mRoute.canDisconnect() ? View.VISIBLE : View.GONE);

        if (mCustomControlView == null) {
            if (mFetchArtTask != null) {
                mFetchArtTask.cancel(true);
            }
            mFetchArtTask = new FetchArtTask();
            mFetchArtTask.execute();
        }
        updateVolumeControlLayout();
        updatePlaybackControlLayout();
    }

    private boolean canShowPlaybackControlLayout() {
        return mCustomControlView == null && (mDescription != null || mState != null);
    }

    /**
     * Returns the height of main media controller which includes playback control and master
     * volume control.
     */
    private int getMainControllerHeight(boolean showPlaybackControl) {
        int height = 0;
        if (showPlaybackControl || mVolumeControlLayout.getVisibility() == View.VISIBLE) {
            height += mMediaMainControlLayout.getPaddingTop()
                    + mMediaMainControlLayout.getPaddingBottom();
            if (showPlaybackControl) {
                height +=  mPlaybackControlLayout.getMeasuredHeight();
            }
            if (mVolumeControlLayout.getVisibility() == View.VISIBLE) {
                height += mVolumeControlLayout.getMeasuredHeight();
            }
            if (showPlaybackControl && mVolumeControlLayout.getVisibility() == View.VISIBLE) {
                height += mDividerView.getMeasuredHeight();
            }
        }
        return height;
    }

    private void updateMediaControlVisibility(boolean canShowPlaybackControlLayout) {
        // TODO: Update the top and bottom padding of the control layout according to the display
        // height.
        mDividerView.setVisibility((mVolumeControlLayout.getVisibility() == View.VISIBLE
                && canShowPlaybackControlLayout) ? View.VISIBLE : View.GONE);
        mMediaMainControlLayout.setVisibility((mVolumeControlLayout.getVisibility() == View.GONE
                && !canShowPlaybackControlLayout) ? View.GONE : View.VISIBLE);
    }

    private void updateLayoutHeight() {
        // We need to defer the update until the first layout has occurred, as we don't yet know the
        // overall visible display size in which the window this view is attached to has been
        // positioned in.
        ViewTreeObserver observer = mDefaultControlLayout.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                mDefaultControlLayout.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                updateLayoutHeightInternal();
            }
        });
    }

    /**
     * Updates the height of views and hide artwork or metadata if space is limited.
     */
    private void updateLayoutHeightInternal() {
        // Measure the size of widgets and get the height of main components.
        int oldHeight = getLayoutHeight(mMediaMainControlLayout);
        setLayoutHeight(mMediaMainControlLayout, ViewGroup.LayoutParams.FILL_PARENT);
        updateMediaControlVisibility(canShowPlaybackControlLayout());
        View decorView = getWindow().getDecorView();
        decorView.measure(
                MeasureSpec.makeMeasureSpec(getWindow().getAttributes().width, MeasureSpec.EXACTLY),
                MeasureSpec.UNSPECIFIED);
        setLayoutHeight(mMediaMainControlLayout, oldHeight);
        int artViewHeight = 0;
        if (mCustomControlView == null && mArtView.getDrawable() instanceof BitmapDrawable) {
            Bitmap art = ((BitmapDrawable) mArtView.getDrawable()).getBitmap();
            if (art != null) {
                artViewHeight = getDesiredArtHeight(art.getWidth(), art.getHeight());
                mArtView.setScaleType(art.getWidth() >= art.getHeight()
                        ? ImageView.ScaleType.FIT_XY : ImageView.ScaleType.FIT_CENTER);
            }
        }
        int mainControllerHeight = getMainControllerHeight(canShowPlaybackControlLayout());
        int volumeGroupListCount = mVolumeGroupList.getAdapter() != null
                ? mVolumeGroupList.getAdapter().getCount() : 0;
        // Scale down volume group list items in landscape mode.
        for (int i = 0; i < mVolumeGroupList.getChildCount(); i++) {
            updateVolumeGroupItemHeight(mVolumeGroupList.getChildAt(i));
        }
        int expandedGroupListHeight = mVolumeGroupListItemHeight * volumeGroupListCount;
        if (volumeGroupListCount > 0) {
            expandedGroupListHeight += mVolumeGroupListPaddingTop;
        }
        expandedGroupListHeight = Math.min(expandedGroupListHeight, mVolumeGroupListMaxHeight);
        int visibleGroupListHeight = mIsGroupExpanded ? expandedGroupListHeight : 0;

        int desiredControlLayoutHeight =
                Math.max(artViewHeight, visibleGroupListHeight) + mainControllerHeight;
        Rect visibleRect = new Rect();
        decorView.getWindowVisibleDisplayFrame(visibleRect);
        // Height of non-control views in decor view.
        // This includes title bar, button bar, and dialog's vertical padding which should be
        // always shown.
        int nonControlViewHeight = mDialogAreaLayout.getMeasuredHeight()
                - mDefaultControlLayout.getMeasuredHeight();
        // Maximum allowed height for controls to fit screen.
        int maximumControlViewHeight = visibleRect.height() - nonControlViewHeight;

        // Show artwork if it fits the screen.
        if (mCustomControlView == null && artViewHeight > 0
                && desiredControlLayoutHeight <= maximumControlViewHeight) {
            mArtView.setVisibility(View.VISIBLE);
            setLayoutHeight(mArtView, artViewHeight);
        } else {
            if (getLayoutHeight(mVolumeGroupList) + mMediaMainControlLayout.getMeasuredHeight()
                    >= mDefaultControlLayout.getMeasuredHeight()) {
                mArtView.setVisibility(View.GONE);
            }
            artViewHeight = 0;
            desiredControlLayoutHeight = visibleGroupListHeight + mainControllerHeight;
        }
        // Show the playback control if it fits the screen.
        if (canShowPlaybackControlLayout()
                && desiredControlLayoutHeight <= maximumControlViewHeight) {
            mPlaybackControlLayout.setVisibility(View.VISIBLE);
        } else {
            mPlaybackControlLayout.setVisibility(View.GONE);
        }
        updateMediaControlVisibility(mPlaybackControlLayout.getVisibility() == View.VISIBLE);
        mainControllerHeight = getMainControllerHeight(
                mPlaybackControlLayout.getVisibility() == View.VISIBLE);
        desiredControlLayoutHeight =
                Math.max(artViewHeight, visibleGroupListHeight) + mainControllerHeight;

        // Limit the volume group list height to fit the screen.
        if (desiredControlLayoutHeight > maximumControlViewHeight) {
            visibleGroupListHeight -= (desiredControlLayoutHeight - maximumControlViewHeight);
            desiredControlLayoutHeight = maximumControlViewHeight;
        }
        // Update the layouts with the computed heights.
        mMediaMainControlLayout.clearAnimation();
        mVolumeGroupList.clearAnimation();
        mDefaultControlLayout.clearAnimation();
        if (mIsGroupListAnimationNeeded) {
            animateLayoutHeight(mMediaMainControlLayout, mainControllerHeight);
            animateLayoutHeight(mVolumeGroupList, visibleGroupListHeight);
            animateLayoutHeight(mDefaultControlLayout, desiredControlLayoutHeight);
        } else {
            setLayoutHeight(mMediaMainControlLayout, mainControllerHeight);
            setLayoutHeight(mVolumeGroupList, visibleGroupListHeight);
            setLayoutHeight(mDefaultControlLayout, desiredControlLayoutHeight);
        }
        mIsGroupListAnimationNeeded = false;
        // Maximize the window size with a transparent layout in advance for smooth animation.
        setLayoutHeight(mExpandableAreaLayout, visibleRect.height());
    }

    private void updateVolumeGroupItemHeight(View item) {
        setLayoutHeight(item, mVolumeGroupListItemHeight);
        View icon = item.findViewById(R.id.mr_volume_item_icon);
        ViewGroup.LayoutParams lp = icon.getLayoutParams();
        lp.width = mVolumeGroupListItemIconSize;
        lp.height = mVolumeGroupListItemIconSize;
        icon.setLayoutParams(lp);
    }

    private void animateLayoutHeight(final View view, int targetHeight) {
        final int startValue = getLayoutHeight(view);
        final int endValue = targetHeight;
        Animation anim = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                int height = startValue - (int) ((startValue - endValue) * interpolatedTime);
                setLayoutHeight(view, height);
            }
        };
        anim.setDuration(mGroupListAnimationDurationMs);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            anim.setInterpolator(mContext, mIsGroupExpanded ? R.interpolator.mr_linear_out_slow_in
                    : R.interpolator.mr_fast_out_slow_in);
        }
        if (view == mVolumeGroupList) {
            anim.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                    mVolumeGroupList.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    mVolumeGroupList.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
                }

                @Override
                public void onAnimationRepeat(Animation animation) { }
            });
        }
        view.startAnimation(anim);
    }

    private void updateVolumeControlLayout() {
        if (isVolumeControlAvailable(mRoute)) {
            if (mVolumeControlLayout.getVisibility() == View.GONE) {
                mVolumeControlLayout.setVisibility(View.VISIBLE);
                mVolumeSlider.setMax(mRoute.getVolumeMax());
                mVolumeSlider.setProgress(mRoute.getVolume());
                if (getGroup() == null) {
                    mGroupExpandCollapseButton.setVisibility(View.GONE);
                } else {
                    mGroupExpandCollapseButton.setVisibility(View.VISIBLE);
                    VolumeGroupAdapter adapter =
                            (VolumeGroupAdapter) mVolumeGroupList.getAdapter();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                }
            }
        } else {
            mVolumeControlLayout.setVisibility(View.GONE);
        }
        updateLayoutHeight();
    }

    private void updatePlaybackControlLayout() {
        if (canShowPlaybackControlLayout()) {
            CharSequence title = mDescription == null ? null : mDescription.getTitle();
            boolean hasTitle = !TextUtils.isEmpty(title);

            CharSequence subtitle = mDescription == null ? null : mDescription.getSubtitle();
            boolean hasSubtitle = !TextUtils.isEmpty(subtitle);

            boolean showTitle = false;
            boolean showSubtitle = false;
            if (mRoute.getPresentationDisplayId()
                    != MediaRouter.RouteInfo.PRESENTATION_DISPLAY_ID_NONE) {
                // The user is currently casting screen.
                mTitleView.setText(R.string.mr_controller_casting_screen);
                showTitle = true;
            } else if (mState == null || mState.getState() == PlaybackStateCompat.STATE_NONE) {
                // Show "No media selected" as we don't yet know the playback state.
                // (Only exception is bluetooth where we don't show anything.)
                if (!mRoute.isDeviceTypeBluetooth()) {
                    mTitleView.setText(R.string.mr_controller_no_media_selected);
                    showTitle = true;
                }
            } else if (!hasTitle && !hasSubtitle) {
                mTitleView.setText(R.string.mr_controller_no_info_available);
                showTitle = true;
            } else {
                if (hasTitle) {
                    mTitleView.setText(title);
                    showTitle = true;
                }
                if (hasSubtitle) {
                    mSubtitleView.setText(subtitle);
                    showSubtitle = true;
                }
            }
            mTitleView.setVisibility(showTitle ? View.VISIBLE : View.GONE);
            mSubtitleView.setVisibility(showSubtitle ? View.VISIBLE : View.GONE);

            if (mState != null) {
                boolean isPlaying = mState.getState() == PlaybackStateCompat.STATE_BUFFERING
                        || mState.getState() == PlaybackStateCompat.STATE_PLAYING;
                boolean supportsPlay = (mState.getActions() & (PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE)) != 0;
                boolean supportsPause = (mState.getActions() & (PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE)) != 0;
                if (isPlaying && supportsPause) {
                    mPlayPauseButton.setVisibility(View.VISIBLE);
                    mPlayPauseButton.setImageResource(MediaRouterThemeHelper.getThemeResource(
                            mContext, R.attr.mediaRoutePauseDrawable));
                    mPlayPauseButton.setContentDescription(mContext.getResources()
                            .getText(R.string.mr_controller_pause));
                } else if (!isPlaying && supportsPlay) {
                    mPlayPauseButton.setVisibility(View.VISIBLE);
                    mPlayPauseButton.setImageResource(MediaRouterThemeHelper.getThemeResource(
                            mContext, R.attr.mediaRoutePlayDrawable));
                    mPlayPauseButton.setContentDescription(mContext.getResources()
                            .getText(R.string.mr_controller_play));
                } else {
                    mPlayPauseButton.setVisibility(View.GONE);
                }
            }
        }
        updateLayoutHeight();
    }

    private boolean isVolumeControlAvailable(MediaRouter.RouteInfo route) {
        return mVolumeControlEnabled && route.getVolumeHandling()
                == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE;
    }

    private static int getLayoutHeight(View view) {
        return view.getLayoutParams().height;
    }

    private static void setLayoutHeight(View view, int height) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        lp.height = height;
        view.setLayoutParams(lp);
    }

    /**
     * Returns desired art height to fit into controller dialog.
     */
    private int getDesiredArtHeight(int originalWidth, int originalHeight) {
        if (originalWidth >= originalHeight) {
            // For landscape art, fit width to dialog width.
            return (int) ((float) mDialogContentWidth * originalHeight / originalWidth + 0.5f);
        }
        // For portrait art, fit height to 16:9 ratio case's height.
        return (int) ((float) mDialogContentWidth * 9 / 16 + 0.5f);
    }

    private final class MediaRouterCallback extends MediaRouter.Callback {
        @Override
        public void onRouteUnselected(MediaRouter router, MediaRouter.RouteInfo route) {
            update();
        }

        @Override
        public void onRouteChanged(MediaRouter router, MediaRouter.RouteInfo route) {
            update();
        }

        @Override
        public void onRouteVolumeChanged(MediaRouter router, MediaRouter.RouteInfo route) {
            SeekBar volumeSlider = mVolumeSliderMap.get(route);
            if (volumeSlider != null && mRouteInVolumeSliderTouched != route) {
                volumeSlider.setProgress(route.getVolume());
            }
        }
    }

    private final class MediaControllerCallback extends MediaControllerCompat.Callback {
        @Override
        public void onSessionDestroyed() {
            if (mMediaController != null) {
                mMediaController.unregisterCallback(mControllerCallback);
                mMediaController = null;
            }
        }

        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            mState = state;
            update();
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            mDescription = metadata == null ? null : metadata.getDescription();
            update();
        }
    }

    private final class ClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            int id = v.getId();
            if (id == BUTTON_STOP_RES_ID || id == BUTTON_DISCONNECT_RES_ID) {
                if (mRoute.isSelected()) {
                    mRouter.unselect(id == BUTTON_STOP_RES_ID ?
                            MediaRouter.UNSELECT_REASON_STOPPED :
                            MediaRouter.UNSELECT_REASON_DISCONNECTED);
                }
                dismiss();
            } else if (id == R.id.mr_control_play_pause) {
                if (mMediaController != null && mState != null) {
                    boolean isPlaying = mState.getState() == PlaybackStateCompat.STATE_PLAYING;
                    if (isPlaying) {
                        mMediaController.getTransportControls().pause();
                    } else {
                        mMediaController.getTransportControls().play();
                    }
                    // Announce the action for accessibility.
                    if (mAccessibilityManager != null && mAccessibilityManager.isEnabled()) {
                        AccessibilityEvent event = AccessibilityEvent.obtain(
                                AccessibilityEventCompat.TYPE_ANNOUNCEMENT);
                        event.setPackageName(mContext.getPackageName());
                        event.setClassName(getClass().getName());
                        int resId = isPlaying ?
                                R.string.mr_controller_pause : R.string.mr_controller_play;
                        event.getText().add(mContext.getString(resId));
                        mAccessibilityManager.sendAccessibilityEvent(event);
                    }
                }
            } else if (id == R.id.mr_close) {
                dismiss();
            }
        }
    }

    private class VolumeChangeListener implements SeekBar.OnSeekBarChangeListener {
        private final Runnable mStopTrackingTouch = new Runnable() {
            @Override
            public void run() {
                if (mRouteInVolumeSliderTouched != null) {
                    SeekBar volumeSlider = mVolumeSliderMap.get(mRouteInVolumeSliderTouched);
                    volumeSlider.setProgress(mRouteInVolumeSliderTouched.getVolume());
                    mRouteInVolumeSliderTouched = null;
                }
            }
        };

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            if (mRouteInVolumeSliderTouched != null) {
                mVolumeSlider.removeCallbacks(mStopTrackingTouch);
            }
            mRouteInVolumeSliderTouched = (MediaRouter.RouteInfo) seekBar.getTag();
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // Defer resetting mVolumeSliderTouched to allow the media route provider
            // a little time to settle into its new state and publish the final
            // volume update.
            mVolumeSlider.postDelayed(mStopTrackingTouch, VOLUME_UPDATE_DELAY_MILLIS);
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) {
                MediaRouter.RouteInfo route = (MediaRouter.RouteInfo) seekBar.getTag();
                if (route.getVolume() != progress) {
                    route.requestSetVolume(progress);
                }
            }
        }
    }

    private class VolumeGroupAdapter extends ArrayAdapter<MediaRouter.RouteInfo> {
        final float mDisabledAlpha;

        public VolumeGroupAdapter(Context context, List<MediaRouter.RouteInfo> objects) {
            super(context, 0, objects);
            mDisabledAlpha = MediaRouterThemeHelper.getDisabledAlpha(context);
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(mContext).inflate(
                        R.layout.mr_controller_volume_item, parent, false);
            } else {
                updateVolumeGroupItemHeight(v);
            }

            MediaRouter.RouteInfo route = getItem(position);
            if (route != null) {
                boolean isEnabled = route.isEnabled();

                TextView routeName = (TextView) v.findViewById(R.id.mr_name);
                routeName.setEnabled(isEnabled);
                routeName.setText(route.getName());

                MediaRouteVolumeSlider volumeSlider =
                        (MediaRouteVolumeSlider) v.findViewById(R.id.mr_volume_slider);
                MediaRouterThemeHelper.setVolumeSliderColor(
                        mContext, volumeSlider, mVolumeGroupList);
                volumeSlider.setTag(route);
                mVolumeSliderMap.put(route, volumeSlider);
                volumeSlider.setHideThumb(!isEnabled);
                volumeSlider.setEnabled(isEnabled);
                if (isEnabled) {
                    if (isVolumeControlAvailable(route)) {
                        volumeSlider.setMax(route.getVolumeMax());
                        volumeSlider.setProgress(route.getVolume());
                        volumeSlider.setOnSeekBarChangeListener(mVolumeChangeListener);
                    } else {
                        volumeSlider.setMax(100);
                        volumeSlider.setProgress(100);
                        volumeSlider.setEnabled(false);
                    }
                }

                ImageView volumeItemIcon =
                        (ImageView) v.findViewById(R.id.mr_volume_item_icon);
                volumeItemIcon.setAlpha(isEnabled ? 0xFF : (int) (0xFF * mDisabledAlpha));
            }
            return v;
        }
    }

    private class FetchArtTask extends AsyncTask<Void, Void, Bitmap> {
        final Bitmap mIconBitmap;
        final Uri mIconUri;
        int mBackgroundColor;

        FetchArtTask() {
            mIconBitmap = mDescription == null ? null : mDescription.getIconBitmap();
            mIconUri = mDescription == null ? null : mDescription.getIconUri();
        }

        @Override
        protected void onPreExecute() {
            if (mArtIconBitmap == mIconBitmap && mArtIconUri == mIconUri) {
                // Already handled the current art.
                cancel(true);
            }
        }

        @Override
        protected Bitmap doInBackground(Void... arg) {
            Bitmap art = null;
            if (mIconBitmap != null) {
                art = mIconBitmap;
            } else if (mIconUri != null) {
                String scheme = mIconUri.getScheme();
                if (!(ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)
                        || ContentResolver.SCHEME_CONTENT.equals(scheme)
                        || ContentResolver.SCHEME_FILE.equals(scheme))) {
                    Log.w(TAG, "Icon Uri should point to local resources.");
                    return null;
                }
                BufferedInputStream stream = null;
                try {
                    stream = new BufferedInputStream(
                            mContext.getContentResolver().openInputStream(mIconUri));

                    // Query art size.
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(stream, null, options);
                    if (options.outWidth == 0 || options.outHeight == 0) {
                        return null;
                    }
                    // Rewind the stream in order to restart art decoding.
                    try {
                        stream.reset();
                    } catch (IOException e) {
                        // Failed to rewind the stream, try to reopen it.
                        stream.close();
                        stream = new BufferedInputStream(mContext.getContentResolver()
                                .openInputStream(mIconUri));
                    }
                    // Calculate required size to decode the art and possibly resize it.
                    options.inJustDecodeBounds = false;
                    int reqHeight = getDesiredArtHeight(options.outWidth, options.outHeight);
                    int ratio = options.outHeight / reqHeight;
                    options.inSampleSize = Math.max(1, Integer.highestOneBit(ratio));
                    if (isCancelled()) {
                        return null;
                    }
                    art = BitmapFactory.decodeStream(stream, null, options);
                } catch (IOException e){
                    Log.w(TAG, "Unable to open: " + mIconUri, e);
                } finally {
                    if (stream != null) {
                        try {
                            stream.close();
                        } catch (IOException e) {
                        }
                    }
                }
            }
            if (art != null && art.getWidth() < art.getHeight()) {
                // Portrait art requires dominant color as background color.
                Palette palette = new Palette.Builder(art).maximumColorCount(1).generate();
                mBackgroundColor = palette.getSwatches().isEmpty()
                        ? 0 : palette.getSwatches().get(0).getRgb();
            }
            return art;
        }

        @Override
        protected void onCancelled() {
            mFetchArtTask = null;
        }

        @Override
        protected void onPostExecute(Bitmap art) {
            mFetchArtTask = null;
            if (mArtIconBitmap != mIconBitmap || mArtIconUri != mIconUri) {
                mArtIconBitmap = mIconBitmap;
                mArtIconUri = mIconUri;

                mArtView.setImageBitmap(art);
                mArtView.setBackgroundColor(mBackgroundColor);
                updateLayoutHeight();
            }
        }
    }
}
