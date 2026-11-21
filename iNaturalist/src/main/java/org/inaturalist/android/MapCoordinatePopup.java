package org.inaturalist.android;

import android.content.Context;
import android.graphics.Point;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;

import java.util.Locale;

/**
 * A draggable popup placed on top of a map. It shows the coordinates + elevation of the point it
 * is pointing at (the green dot at its bottom). Tapping the popup copies the coordinates and
 * elevation to the clipboard. Dragging it moves the point and updates the values.
 *
 * The container must be a RelativeLayout / FrameLayout that holds the map and fills the same area.
 */
public class MapCoordinatePopup {
    // Only used when the tile under the popup isn't in memory yet (otherwise the elevation is instant)
    private static final long ELEVATION_DEBOUNCE_MS = 150;

    private final Context mContext;
    private final GoogleMap mMap;
    private final ViewGroup mContainer;
    private final View mView;
    private final TextView mCoordinates;
    private final TextView mElevation;
    private final View mDot;
    private final int mTouchSlop;

    private LatLng mLatLng;
    private Double mElevationMeters;
    private boolean mElevationFailed;
    private int mRequestId = 0;

    private final Runnable mFetchElevation = new Runnable() {
        @Override
        public void run() {
            fetchElevation();
        }
    };

    public MapCoordinatePopup(Context context, GoogleMap map, ViewGroup container) {
        mContext = context;
        mMap = map;
        mContainer = container;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        mView = LayoutInflater.from(context).inflate(R.layout.map_coordinate_popup, container, false);
        mCoordinates = mView.findViewById(R.id.popup_coordinates);
        mElevation = mView.findViewById(R.id.popup_elevation);
        mDot = mView.findViewById(R.id.popup_dot);
        mView.setVisibility(View.GONE);
        container.addView(mView);

        // When the card's size changes (e.g. the elevation goes from 999 m to 1,000 m so the text gets wider),
        // keep the dot - the point being shown - exactly where it was, instead of letting it shift
        mView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int oldWidth = oldRight - oldLeft;
                int oldHeight = oldBottom - oldTop;
                int newWidth = right - left;
                int newHeight = bottom - top;

                if ((oldWidth == 0) || (oldHeight == 0)) return; // First layout - show() positions it
                if ((oldWidth == newWidth) && (oldHeight == newHeight)) return;
                if (mView.getVisibility() != View.VISIBLE) return;

                // The dot is horizontally centered at the bottom of the popup
                mView.setX(mView.getX() + (oldWidth - newWidth) / 2f);
                mView.setY(mView.getY() + (oldHeight - newHeight));
            }
        });

        mView.findViewById(R.id.popup_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });

        mView.setOnTouchListener(new View.OnTouchListener() {
            private float mDownRawX, mDownRawY, mDownAnchorX, mDownAnchorY;
            private boolean mMoved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDownRawX = event.getRawX();
                        mDownRawY = event.getRawY();
                        // Track the position of the dot (not of the card), since the card's size can change
                        Point anchorOffset = getAnchorOffset();
                        mDownAnchorX = mView.getX() + anchorOffset.x;
                        mDownAnchorY = mView.getY() + anchorOffset.y;
                        mMoved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - mDownRawX;
                        float dy = event.getRawY() - mDownRawY;
                        if (!mMoved && (Math.abs(dx) < mTouchSlop) && (Math.abs(dy) < mTouchSlop)) return true;

                        mMoved = true;
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);

                        Point offset = getAnchorOffset();
                        mView.setX(mDownAnchorX + dx - offset.x);
                        mView.setY(mDownAnchorY + dy - offset.y);
                        onDragged();
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (mMoved) {
                            // Finished dragging - make sure we have the elevation of the final point
                            mView.removeCallbacks(mFetchElevation);
                            fetchElevation(); // Cheap if the tile is cached - also prefetches the surrounding tiles
                        } else {
                            // A tap copies only the line that was tapped: the top half of the card
                            // copies the coordinates, the bottom half copies the elevation
                            int[] elevationLocation = new int[2];
                            mElevation.getLocationOnScreen(elevationLocation);
                            if (event.getRawY() < elevationLocation[1]) {
                                copyCoordinates();
                            } else {
                                copyElevation();
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
    }

    public boolean isShowing() {
        return mView.getVisibility() == View.VISIBLE;
    }

    /** Shows the popup, pointing at the given location */
    public void show(LatLng latLng) {
        setLocation(latLng);
        mView.setVisibility(View.VISIBLE);
        mView.bringToFront();
        reposition();
        // Size isn't known until the view is laid out the first time
        mView.post(new Runnable() {
            @Override
            public void run() {
                reposition();
            }
        });

        mView.removeCallbacks(mFetchElevation);
        fetchElevation();
    }

    public void hide() {
        mRequestId++; // Ignore any in-flight elevation lookups
        mView.removeCallbacks(mFetchElevation);
        mView.setVisibility(View.GONE);
    }

    /** Should be called when the map's camera moves, so the popup stays on top of its point */
    public void onCameraMove() {
        if (isShowing()) reposition();
    }

    private void setLocation(LatLng latLng) {
        double lng = latLng.longitude;
        // Normalize (the map can wrap around)
        lng = ((lng + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;

        mLatLng = new LatLng(latLng.latitude, lng);

        // If the elevation tile is already in memory, this is just a pixel read - so the elevation is
        // shown immediately, without any "loading" state (this is what makes dragging feel live)
        mElevationMeters = ElevationUtils.getCachedElevation(mLatLng.latitude, mLatLng.longitude);
        mElevationFailed = false;
        refreshText();
    }

    private void refreshText() {
        mCoordinates.setText(String.format(Locale.ENGLISH, "%.5f, %.5f", mLatLng.latitude, mLatLng.longitude));

        if (mElevationMeters != null) {
            mElevation.setText(String.format(mContext.getString(R.string.map_popup_elevation), ElevationUtils.format(mElevationMeters)));
        } else if (mElevationFailed) {
            mElevation.setText(R.string.map_popup_elevation_unavailable);
        } else {
            mElevation.setText(R.string.map_popup_elevation_loading);
        }
    }

    // Position of the popup's pointer (the dot) relative to the popup itself
    private Point getAnchorOffset() {
        return new Point(mView.getWidth() / 2, mView.getHeight() - (mDot.getHeight() / 2));
    }

    private void reposition() {
        if ((mLatLng == null) || (mView.getWidth() == 0)) return;

        Point screen = mMap.getProjection().toScreenLocation(mLatLng);
        Point offset = getAnchorOffset();
        mView.setX(screen.x - offset.x);
        mView.setY(screen.y - offset.y);
    }

    // User is dragging the popup - compute the new point from where its pointer is now
    private void onDragged() {
        Point offset = getAnchorOffset();
        LatLng latLng = mMap.getProjection().fromScreenLocation(new Point(
                Math.round(mView.getX()) + offset.x,
                Math.round(mView.getY()) + offset.y));

        setLocation(latLng);

        mView.removeCallbacks(mFetchElevation);
        if (mElevationMeters == null) {
            // Tile not in memory yet (e.g. dragged past the prefetched area) - download it, but don't
            // start a download for every pixel of movement
            mView.postDelayed(mFetchElevation, ELEVATION_DEBOUNCE_MS);
        }
    }

    private void fetchElevation() {
        if (mLatLng == null) return;

        final int requestId = ++mRequestId;
        final LatLng requested = mLatLng;

        ElevationUtils.getElevation(requested.latitude, requested.longitude, new ElevationUtils.Callback() {
            @Override
            public void onElevation(Double elevationMeters) {
                // Ignore stale results (popup moved / closed meanwhile)
                if ((requestId != mRequestId) || (!isShowing())) return;

                mElevationMeters = elevationMeters;
                mElevationFailed = (elevationMeters == null);
                refreshText();
            }
        });
    }

    // Copies only the coordinates, e.g. "37.774900,-122.419400"
    private void copyCoordinates() {
        if (mLatLng == null) return;

        FileUtils.copyToClipBoard(mContext, String.format(Locale.ENGLISH, "%.6f,%.6f", mLatLng.latitude, mLatLng.longitude));
        Toast.makeText(mContext, mContext.getString(R.string.coordinates_copied_to_clipboard), Toast.LENGTH_LONG).show();
    }

    // Copies only the elevation, e.g. "1234 m / 4049 ft"
    private void copyElevation() {
        if (mElevationMeters == null) {
            // Still loading / failed - nothing to copy
            Toast.makeText(mContext, mElevation.getText(), Toast.LENGTH_SHORT).show();
            return;
        }

        FileUtils.copyToClipBoard(mContext, ElevationUtils.formatPlain(mElevationMeters));
        Toast.makeText(mContext, mContext.getString(R.string.elevation_copied_to_clipboard), Toast.LENGTH_LONG).show();
    }
}
