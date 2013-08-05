/**
 * Copyright (C) 2013 The CyanogenMod Project
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.cyanogenmod.focal.ui;

import android.animation.Animator;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;

import org.cyanogenmod.focal.CameraActivity;
import org.cyanogenmod.focal.R;
import org.cyanogenmod.focal.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * This class handles the review drawer that can be opened by swiping down
 */
public class ReviewDrawer extends LinearLayout {
    public final static String TAG = "ReviewDrawer";

    private final static long DRAWER_TOGGLE_DURATION = 400;
    private final static int DRAWER_QUICK_REVIEW_SIZE_DP = 120;
    private final static String GALLERY_CAMERA_BUCKET = "Camera";
    private int mThumbnailSize;

    private List<Integer> mImages;

    private Handler mHandler;
    private ListView mImagesList;
    private ImageView mReviewedImage;
    private ViewGroup mReviewedImageContainer;
    private int mReviewedImageOrientation;
    private ImageListAdapter mImagesListAdapter;
    private Bitmap mReviewedBitmap;
    private int mReviewedImageId;
    private int mCurrentOrientation;
    private boolean mIsOpen;
    private GestureDetector mGestureDetector;


    public ReviewDrawer(Context context) {
        super(context);
        initialize();
    }

    public ReviewDrawer(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public ReviewDrawer(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initialize();
    }

    private void initialize() {
        mHandler = new Handler();
        mImages = new ArrayList<Integer>();
        mThumbnailSize = getContext().getResources().getDimensionPixelSize(R.dimen.review_drawer_thumb_size);

        setOrientation(LinearLayout.VERTICAL);

        // Default hidden
        setAlpha(0.0f);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                setTranslationY(-getMeasuredHeight());
            }
        });

        // Setup the list adapter
        mImagesListAdapter = new ImageListAdapter();

    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mImagesList = (ListView) findViewById(R.id.list_reviewed_images);
        if (mImagesList == null) return;

        mImagesList.setAdapter(mImagesListAdapter);
        mImagesList.setOnItemClickListener(new ImageListClickListener());

        mReviewedImage = (ImageView) findViewById(R.id.reviewed_image);
        mReviewedImageContainer = (ViewGroup) findViewById(R.id.reviewed_image_container);

        // Load pictures or videos from gallery
        if (CameraActivity.getCameraMode() == CameraActivity.CAMERA_MODE_VIDEO) {
            updateFromGallery(false);
        } else {
            updateFromGallery(true);
        }

        // Make sure drawer is initially closed
        setTranslationY(-99999);

        // Setup gesture detection
        mGestureDetector = new GestureDetector(getContext(), new ReviewedGestureListener());
        View.OnTouchListener touchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    clampReviewedImageSliding();
                }
                mGestureDetector.onTouchEvent(ev);

                return true;
            }
        };

        mReviewedImageContainer.setOnTouchListener(touchListener);
    }

    /**
     * Clears the list of images and reload it from the Gallery (MediaStore)
     * This method is threaded!
     *
     * @param images True to get images, false to get videos
     */
    public void updateFromGallery(final boolean images) {
        new Thread() {
            public void run() {
                updateFromGallerySynchronous(images);
            }
        }.start();
    }

    /**
     * Clears the list of images and reload it from the Gallery (MediaStore)
     * This method is synchronous, see updateFromGallery for the threaded one.
     *
     * @param images True to get images, false to get videos
     */
    public void updateFromGallerySynchronous(boolean images) {
        mImages.clear();

        String[] columns = null;
        String orderBy = null;
        if (images) {
            columns = new String[]{MediaStore.Images.Media.DATA, MediaStore.Images.Media._ID};
            orderBy = MediaStore.Images.Media.DATE_TAKEN + " ASC";
        } else {
            columns = new String[]{MediaStore.Video.Media.DATA, MediaStore.Video.Media._ID};
            orderBy = MediaStore.Video.Media.DATE_TAKEN + " ASC";
        }

        // Select only the images that has been taken from the Camera
        ContentResolver cr = getContext().getContentResolver();
        if (cr == null) {
            Log.e(TAG, "No content resolver!");
            return;
        }
        Cursor cursor = null;

        if (images) {
            cursor = cr.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " LIKE ?",
                    new String[]{GALLERY_CAMERA_BUCKET}, orderBy);
        } else {
            cursor = cr.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, columns, MediaStore.Video.Media.BUCKET_DISPLAY_NAME + " LIKE ?",
                    new String[]{GALLERY_CAMERA_BUCKET}, orderBy);
        }

        if (cursor == null) {
            Log.e(TAG, "Null cursor from MediaStore!");
            return;
        }

        final int imageColumnIndex = cursor.getColumnIndex(images ? MediaStore.Images.Media._ID : MediaStore.Video.Media._ID);
        final Cursor finalCursor = cursor;

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < finalCursor.getCount(); i++) {
                    finalCursor.moveToPosition(i);

                    int id = finalCursor.getInt(imageColumnIndex);
                    addImageToList(id);
                }
            }
        });

        if (cursor.getCount() > 0) {
            // Set the default reviewed image to the last image
            cursor.moveToLast();
            final int firstPictureId = cursor.getInt(imageColumnIndex);
            setPreviewedImage(firstPictureId);
        }
    }

    /**
     * Queries the Media service for image orientation
     *
     * @param id The id of the gallery image
     * @return The orientation of the image, or 0 if it failed
     */
    public int getCameraPhotoOrientation(final int id) {
        if (CameraActivity.getCameraMode() == CameraActivity.CAMERA_MODE_VIDEO) {
            return 0;
        } else {
            Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(Integer.toString(id)).build();;
            String[] orientationColumn = new String[]{MediaStore.Images.Media.ORIENTATION};

            Cursor cur = getContext().getContentResolver().query(uri, orientationColumn, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                return cur.getInt(cur.getColumnIndex(orientationColumn[0]));
            } else {
                return 0;
            }
        }
    }

    /**
     * Sets the previewed image in the review drawer
     *
     * @param id The id the of the image
     */
    public void setPreviewedImage(final int id) {
        new Thread() {
            public void run() {
                if (mReviewedBitmap != null) {
                    recycleBitmap(mReviewedBitmap);
                }
                mReviewedImageId = id;
                if (CameraActivity.getCameraMode() == CameraActivity.CAMERA_MODE_VIDEO) {
                    mReviewedBitmap = MediaStore.Video.Thumbnails.getThumbnail(
                            getContext().getContentResolver(), id,
                            MediaStore.Video.Thumbnails.MINI_KIND, null);
                } else {
                    mReviewedBitmap = MediaStore.Images.Thumbnails.getThumbnail(
                            getContext().getContentResolver(), id,
                            MediaStore.Images.Thumbnails.MINI_KIND, null);
                }

                mReviewedImageOrientation = getCameraPhotoOrientation(id);

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mReviewedImage.setImageBitmap(mReviewedBitmap);
                        notifyOrientationChanged(mCurrentOrientation);
                    }
                });
            }
        }.start();
    }

    /**
     * Add an image at the head of the image ribbon
     *
     * @param id The id of the image from the MediaStore
     */
    public void addImageToList(int id) {
        mImagesListAdapter.addImage(id);
    }

    public void notifyOrientationChanged(int orientation) {
        mCurrentOrientation = orientation;
        mReviewedImage.animate().rotation(mReviewedImageOrientation + orientation)
                .setDuration(200).setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void openInGallery(int imageId) {
        if (imageId > 0) {
            Uri uri = null;
            if (CameraActivity.getCameraMode() == CameraActivity.CAMERA_MODE_VIDEO) {
                uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(Integer.toString(imageId)).build();
            } else {
                uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(Integer.toString(imageId)).build();
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            try {
                getContext().startActivity(intent);
            } catch (ActivityNotFoundException e) {
                CameraActivity.notify(getContext().getString(R.string.no_video_player), 2000);
            }
        }
    }

    /**
     * Sets the review drawer to temporary hide, by reducing alpha to a very low
     * level
     *
     * @param enabled To hide or not to hide, that is the question
     */
    public void setTemporaryHide(boolean enabled) {
        float alpha = (enabled ? 0.2f : 1.0f);
        animate().alpha(alpha).setDuration(200).start();
    }

    /**
     * Returns whether or not the review drawer is FULLY open (ie. not in
     * quick review mode)
     */
    public boolean isOpen() {
        return mIsOpen;
    }

    /**
     * Normally opens the review drawer (animation)
     */
    public void open() {
        mReviewedImage.setVisibility(View.VISIBLE);
        openImpl(1.0f);
    }

    private void openImpl(float alpha) {
        mIsOpen = true;
        setVisibility(View.VISIBLE);
        animate().setDuration(DRAWER_TOGGLE_DURATION).setInterpolator(new AccelerateInterpolator())
                .translationY(0.0f).setListener(null).alpha(alpha).start();
    }

    /**
     * Normally closes the review drawer (animation)
     */
    public void close() {
        mIsOpen = false;
        animate().setDuration(DRAWER_TOGGLE_DURATION).setInterpolator(new DecelerateInterpolator())
                .translationY(-getMeasuredHeight()).alpha(0.0f).setListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {

            }

            @Override
            public void onAnimationEnd(Animator animator) {
                setVisibility(View.GONE);
            }

            @Override
            public void onAnimationCancel(Animator animator) {

            }

            @Override
            public void onAnimationRepeat(Animator animator) {

            }
        }).start();
    }

    /**
     * Slide the review drawer of the specified distance on the X axis
     *
     * @param distance
     */
    public void slide(float distance) {
        if (distance > 1) {
            mReviewedImage.setVisibility(View.VISIBLE);
        }

        float finalPos = getTranslationY() + distance;
        if (finalPos > 0) finalPos = 0;

        setTranslationY(finalPos);

        if (getAlpha() == 0.0f) {
            setVisibility(View.VISIBLE);
            setAlpha(1.0f);
        }
    }

    public void clampSliding() {
        if (getTranslationY() < -getMeasuredHeight() / 2) {
            close();
        } else {
            openImpl(getAlpha());
        }
    }

    public void clampReviewedImageSliding() {
        if (mReviewedImage.getTranslationY() < -mReviewedImage.getHeight() / 4) {
            mReviewedImage.animate().translationY(-mReviewedImage.getHeight()).alpha(0.0f)
                    .setDuration(300).start();
            removeReviewedImage();
        } else if (mReviewedImage.getTranslationY() > mReviewedImage.getHeight() / 4) {
            mReviewedImage.animate().translationY(mReviewedImage.getHeight()).alpha(0.0f)
                    .setDuration(300).start();
            removeReviewedImage();
        } else {
            mReviewedImage.animate().translationY(0).alpha(1.0f)
                    .setDuration(300).start();
        }
    }

    /**
     * Removes the currently reviewed image from the
     * internal memory.
     */
    public void removeReviewedImage() {
        Util.removeFromGallery(getContext().getContentResolver(), mReviewedImageId);
        if (CameraActivity.getCameraMode() == CameraActivity.CAMERA_MODE_VIDEO) {
            updateFromGallery(false);
        } else {
            updateFromGallery(true);
        }

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mImages.size() > 0) {
                    int imageId = mImages.get(0);
                    setPreviewedImage(imageId);
                    mReviewedImageId = imageId;
                    mReviewedImage.animate().translationY(0.0f).alpha(1.0f).setDuration(300).start();
                }
            }
        }, 300);
        // XXX: Undo popup
    }

    private void recycleBitmap(final Bitmap bmp) {
        // We delay the recycle a bit to avoid crashes when scrolling or doing things too fast
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                bmp.recycle();
            }
        }, 1000);
    }

    /**
     * Open the drawer in Quick Review mode
     */
    public void openQuickReview() {
        mReviewedImage.setVisibility(View.GONE);
        openImpl(0.5f);
    }


    /**
     * Class that handles the clicks on the image list
     */
    private class ImageListClickListener implements AdapterView.OnItemClickListener {
        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
            int imageId = mImages.get(i);
            setPreviewedImage(imageId);
            mReviewedImageId = imageId;
        }
    }

    /**
     * Handles the swipe and tap gestures on the reviewed image
     */
    public class ReviewedGestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_MIN_DISTANCE = 10;
        private final float DRAG_MIN_DISTANCE = Util.dpToPx(getContext(), 5.0f);
        private static final int SWIPE_MAX_OFF_PATH = 80;
        private static final int SWIPE_THRESHOLD_VELOCITY = 800;

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            openInGallery(mReviewedImageId);

            return super.onSingleTapConfirmed(e);
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            return super.onDoubleTap(e);
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
                                float distanceY) {
            try {
                float dY = e2.getY() - e1.getY();

                if (Math.abs(e1.getX() - e2.getX()) < SWIPE_MAX_OFF_PATH) {
                    if (Math.abs(e1.getY() - e2.getY()) > SWIPE_MIN_DISTANCE) {
                        mReviewedImage.setTranslationY(dY);
                    }
                }
            } catch (Exception e) {
                // nothing
            }

            float alpha = 1.0f - Math.abs(mReviewedImage.getTranslationY() / mReviewedImage.getMeasuredHeight());
            mReviewedImage.setAlpha(alpha);

            mReviewedImage.invalidate();
            return true;
        }
    }


    /**
     * Adapter responsible for showing the images in the list of the review drawer
     */
    private class ImageListAdapter extends BaseAdapter {
        public ImageListAdapter() {

        }

        public void addImage(int id) {
            mImages.add(0, id);
            mHandler.post(new Runnable() {
                public void run() {
                    ImageListAdapter.this.notifyDataSetChanged();
                }
            });
        }

        @Override
        public boolean areAllItemsEnabled() {
            return true;
        }

        @Override
        public boolean isEnabled(int i) {
            return true;
        }


        @Override
        public int getCount() {
            return mImages.size();
        }

        @Override
        public Integer getItem(int i) {
            return mImages.get(i);
        }

        @Override
        public long getItemId(int i) {
            return mImages.get(i);
        }

        @Override
        public View getView(final int i, View convertView, ViewGroup viewGroup) {
            ImageView imageView;

            // Recycle the view if any exists, remove and recycle its bitmap
            if (convertView == null) {
                imageView = new ImageView(getContext());
            } else {
                imageView = (ImageView) convertView;
                BitmapDrawable bitmapDrawable = ((BitmapDrawable) imageView.getDrawable());

                if (bitmapDrawable != null) {
                    Bitmap bmp = bitmapDrawable.getBitmap();
                    if (bmp != null) {
                        recycleBitmap(bmp);
                    }
                }

                imageView.setImageBitmap(null);
            }

            // Hide the image for a nice transition while scrolling
            imageView.setAlpha(0.0f);
            imageView.setMinimumHeight(mThumbnailSize);

            // Thread the loading to not hang the UI
            final ImageView finalImageView = imageView;
            new Thread() {
                public void run() {
                    final Bitmap thumbnail = CameraActivity.getCameraMode() == CameraActivity.CAMERA_MODE_VIDEO ?
                            (MediaStore.Video.Thumbnails.getThumbnail(
                            getContext().getContentResolver(), mImages.get(i),
                            MediaStore.Video.Thumbnails.MICRO_KIND, null)) :
                            (MediaStore.Images.Thumbnails.getThumbnail(
                            getContext().getContentResolver(), mImages.get(i),
                            MediaStore.Images.Thumbnails.MICRO_KIND, null));

                    if (thumbnail == null) {
                        Log.e(TAG, "Thumbnail is null!");
                        return;
                    }

                    // Thumbnail is ready, apply it to the ImageView in the UI thread and animate it
                    mHandler.post(new Runnable() {
                        public void run() {
                            // If we shoot really fast, the thumbnail might be recycled
                            // even before we displayed it, resulting in a crash
                            if (!thumbnail.isRecycled()) {
                                finalImageView.setImageBitmap(thumbnail);
                            }

                            finalImageView.animate().alpha(1.0f).setDuration(100).start();
                        }
                    });

                }
            }.start();

            return imageView;
        }


    }
}
