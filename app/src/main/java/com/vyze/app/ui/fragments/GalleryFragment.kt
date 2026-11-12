package com.vyze.app.ui.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.vyze.app.databinding.FragmentGalleryBinding

/**
 * Gallery fragment for viewing images.
 *
 * Simplified for VLM architecture — no object detection or bounding boxes.
 * Users can pick an image from their gallery and the VLM can describe it.
 */
class GalleryFragment : Fragment() {

    private var _fragmentGalleryBinding: FragmentGalleryBinding? = null
    private val fragmentGalleryBinding get() = _fragmentGalleryBinding!!

    /** Currently displayed bitmap — recycled on replace/destroy (#60). */
    private var currentBitmap: Bitmap? = null

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { mediaUri ->
                val mimeType = context?.contentResolver?.getType(mediaUri)
                if (mimeType?.startsWith("image") == true) {
                    loadImage(mediaUri)
                } else {
                    Toast.makeText(requireContext(), "Unsupported file type.", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _fragmentGalleryBinding = FragmentGalleryBinding.inflate(inflater, container, false)
        return fragmentGalleryBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentGalleryBinding.fabGetContent.setOnClickListener {
            getContent.launch(arrayOf("image/*"))
        }
    }

    override fun onPause() {
        if (fragmentGalleryBinding.videoView.isPlaying) {
            fragmentGalleryBinding.videoView.stopPlayback()
        }
        fragmentGalleryBinding.videoView.visibility = View.GONE
        super.onPause()
    }

    private fun loadImage(uri: Uri) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(requireActivity().contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.setTargetSampleSize(
                        companionSampleSizeForMaxEdge(info.size.width, info.size.height)
                    )
                }.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                decodeSampledLegacy(uri)
            }

            if (bitmap != null) {
                val previous = currentBitmap
                currentBitmap = bitmap
                fragmentGalleryBinding.imageResult.setImageBitmap(bitmap)
                previous?.recycle()
                fragmentGalleryBinding.imageResult.visibility = View.VISIBLE
                fragmentGalleryBinding.tvPlaceholder.visibility = View.GONE
            } else {
                Toast.makeText(requireContext(), "Failed to load image.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image: ${e.message}", e)
            Toast.makeText(requireContext(), "Failed to load image.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Pre-P decode with the same downsample math — getBitmap has no
     * sampling option, so bounds first, then a sampled decode (#60).
     */
    private fun decodeSampledLegacy(uri: Uri): Bitmap? {
        val resolver = requireActivity().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Throwable) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = companionSampleSizeForMaxEdge(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Throwable) {
            null
        }
    }

    override fun onDestroyView() {
        // Drop the view-held bitmap before the binding (#60) — without
        // this the pixels outlive the view (and the binding leaks too).
        fragmentGalleryBinding.imageResult.setImageDrawable(null)
        currentBitmap?.recycle()
        currentBitmap = null
        _fragmentGalleryBinding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "GalleryFragment"

        /** Longest edge kept — full-res photos OOM low-tier phones (#60). */
        const val GALLERY_MAX_EDGE_PX = 2048

        /**
         * Power-of-two downsample so the longest edge fits
         * [GALLERY_MAX_EDGE_PX]. Pure — JVM-pinned (GallerySampleSizeTest).
         */
        fun companionSampleSizeForMaxEdge(width: Int, height: Int): Int {
            val longest = maxOf(width, height)
            if (longest <= GALLERY_MAX_EDGE_PX) return 1
            var sample = 1
            while (longest / sample > GALLERY_MAX_EDGE_PX) sample *= 2
            return sample
        }
    }
}
