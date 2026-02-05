package de.markusfisch.android.shadereditor.fragment;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.io.InputStream;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.graphics.BitmapEditor;

public class Sampler2dPropertiesFragment extends AbstractSamplerPropertiesFragment {
	private static final String IMAGE_URI = "image_uri";
	private static final String CROP_RECT = "crop_rect";
	private static final String ROTATION = "rotation";

	private Uri imageUri;
	private RectF cropRect;
	private float imageRotation;

	public static Fragment newInstance(
			Uri uri,
			RectF rect,
			float rotation) {
		Bundle args = new Bundle();
		args.putParcelable(IMAGE_URI, uri);
		args.putParcelable(CROP_RECT, rect);
		args.putFloat(ROTATION, rotation);

		Sampler2dPropertiesFragment fragment =
				new Sampler2dPropertiesFragment();
		fragment.setArguments(args);

		return fragment;
	}

	@Override
	public View onCreateView(
			@NonNull LayoutInflater inflater,
			ViewGroup container,
			Bundle state) {
		Activity activity = getActivity();
		if (activity == null) {
			return null;
		}
		activity.setTitle(R.string.texture_properties);

		Bundle args;
		View view;

		if ((args = getArguments()) == null ||
				(imageUri = args.getParcelable(IMAGE_URI)) == null ||
				(cropRect = args.getParcelable(CROP_RECT)) == null ||
				(view = initView(activity, inflater, container)) == null) {
			activity.finish();
			return null;
		}

		imageRotation = args.getFloat(ROTATION);

		// Calculate default dimensions from the cropped region
		int[] imageDimensions = getImageDimensions(activity, imageUri);
		if (imageDimensions != null) {
			int origWidth = imageDimensions[0];
			int origHeight = imageDimensions[1];
			// Apply rotation to dimensions
			if (imageRotation == 90 || imageRotation == 270) {
				int temp = origWidth;
				origWidth = origHeight;
				origHeight = temp;
			}
			// Calculate cropped size
			int croppedWidth = Math.round(cropRect.width() * origWidth);
			int croppedHeight = Math.round(cropRect.height() * origHeight);
			setDefaultDimensions(croppedWidth, croppedHeight);
		}

		return view;
	}

	private static int[] getImageDimensions(Context context, Uri uri) {
		try (InputStream in = context.getContentResolver().openInputStream(uri)) {
			if (in == null) {
				return null;
			}
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inJustDecodeBounds = true;
			BitmapFactory.decodeStream(in, null, options);
			return new int[]{options.outWidth, options.outHeight};
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	protected int saveSampler(
			Context context,
			String name,
			int width,
			int height) {
		return saveTexture(
				context,
				BitmapEditor.getBitmapFromUri(
						context,
						imageUri,
						MAX_TEXTURE_SIZE),
				cropRect,
				imageRotation,
				name,
				width,
				height);
	}

	private static int saveTexture(
			Context context,
			Bitmap bitmap,
			RectF rect,
			float rotation,
			String name,
			int width,
			int height) {
		if ((bitmap = BitmapEditor.crop(bitmap, rect, rotation)) == null) {
			return R.string.illegal_rectangle;
		}

		DataSource dataSource = Database.getInstance(context).getDataSource();

		if (dataSource.texture.insertTexture(
				name,
				Bitmap.createScaledBitmap(bitmap, width, height, true)) < 1) {
			return R.string.name_already_taken;
		}

		return 0;
	}
}
