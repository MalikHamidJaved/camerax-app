package com.example.cameraxsample.ui.video

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.util.Log
import android.widget.MediaController
import androidx.navigation.fragment.navArgs
import android.util.TypedValue
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.example.cameraxsample.R
import com.example.cameraxsample.Utils.DimensionData
import com.example.cameraxsample.Utils.Utils
import com.example.cameraxsample.bottomSheet.PropertiesBSFragment
import com.example.cameraxsample.bottomSheet.StickerBSFragment
import com.example.cameraxsample.databinding.FragmentVideoViewerBinding
import com.example.cameraxsample.dialog.TextEditorDialogFragment
import com.example.cameraxsample.photoeditor.*
import com.example.cameraxsample.preview.PreviewVideoActivity
import com.example.cameraxsample.preview.VideoPreviewActivity
import com.github.hiteshsondhi88.libffmpeg.FFmpeg
import com.github.hiteshsondhi88.libffmpeg.FFmpegExecuteResponseHandler
import com.github.hiteshsondhi88.libffmpeg.FFmpegLoadBinaryResponseHandler
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegCommandAlreadyRunningException
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.lang.RuntimeException

/**
 * VideoViewerFragment:
 *      Accept MediaStore URI and play it with VideoView (Also displaying file size and location)
 *      Note: Might be good to retrieve the encoded file mime type (not based on file type)
 */
class VideoViewerFragment : androidx.fragment.app.Fragment(),
    OnPhotoEditorListener, PropertiesBSFragment.Properties, View.OnClickListener,
    StickerBSFragment.StickerListener {
    private val args: VideoViewerFragmentArgs by navArgs()

    // This property is only valid between onCreateView and onDestroyView.
    private var _binding: FragmentVideoViewerBinding? = null
    private val binding get() = _binding!!

    private var mPhotoEditor: PhotoEditor? = null
    private val globalVideoUrl = ""
    private var propertiesBSFragment: PropertiesBSFragment? = null
    private var mStickerBSFragment: StickerBSFragment? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoPath: String? = ""
    private var imagePath = ""
    private var exeCmd: ArrayList<String?>? = null
    var fFmpeg: FFmpeg? = null
    private var newCommand: Array<String?>? = null
    private var progressDialog: ProgressDialog? = null
    private var originalDisplayWidth = 0
    private var originalDisplayHeight = 0
    private var newCanvasWidth = 0
    private var newCanvasHeight = 0
    private var DRAW_CANVASW = 0
    private var DRAW_CANVASH = 0
    private val onCompletionListener: MediaPlayer.OnCompletionListener =
        OnCompletionListener { mediaPlayer -> mediaPlayer.start() }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoViewerBinding.inflate(inflater, container, false)
        // UI adjustment + hacking to display VideoView use tips / capture result
        val tv = TypedValue()
        if (requireActivity().theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            val actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
//            binding.videoViewerTips.y  = binding.videoViewerTips.y - actionBarHeight
        }

        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            showVideo(args.uri)
        } else {
            // force MediaScanner to re-scan the media file.
            val path = getAbsolutePathFromUri(args.uri) ?: return
            MediaScannerConnection.scanFile(
                context, arrayOf(path), null
            ) { _, uri ->
                // playback video on main thread with VideoView
                if (uri != null) {
                    lifecycleScope.launch {
                        showVideo(uri)
                    }
                }
            }
        }

        initViews()

    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /**
     * A helper function to play the recorded video. Note that VideoView/MediaController auto-hides
     * the play control menus, touch on the video area would bring it back for 3 second.
     * This functionality not really related to capture, provided here for convenient purpose to view:
     *   - the captured video
     *   - the file size and location
     */
    private fun showVideo(uri : Uri) {
        val fileSize = getFileSizeFromUri(uri)
        if (fileSize == null || fileSize <= 0) {
            Log.e("VideoViewerFragment", "Failed to get recorded file size, could not be played!")
            return
        }

        val filePath = getAbsolutePathFromUri(uri) ?: return
        videoPath = filePath
        val fileInfo = "FileSize: $fileSize\n $filePath"
        Log.i("VideoViewerFragment", fileInfo)
//        binding.videoViewerTips.text = fileInfo

        val mc = MediaController(requireContext())
        binding.videoViewer.apply {
            setVideoURI(uri)
            setMediaController(mc)
            requestFocus()
        }.start()
        mc.show(0)
    }

    /**
     * A helper function to get the captured file location.
     */
    private fun getAbsolutePathFromUri(contentUri: Uri): String? {
        var cursor:Cursor? = null
        return try {
            cursor = requireContext()
                .contentResolver
                .query(contentUri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)
            if (cursor == null) {
                return null
            }
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            cursor.getString(columnIndex)
        } catch (e: RuntimeException) {
            Log.e("VideoViewerFragment", String.format(
                "Failed in getting absolute path for Uri %s with Exception %s",
                contentUri.toString(), e.toString()
            )
            )
            null
        } finally {
            cursor?.close()
        }
    }

    /**
     * A helper function to retrieve the captured file size.
     */
    private fun getFileSizeFromUri(contentUri: Uri): Long? {
        val cursor = requireContext()
            .contentResolver
            .query(contentUri, null, null, null, null)
            ?: return null

        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        cursor.moveToFirst()

        cursor.use {
            return it.getLong(sizeIndex)
        }
    }


    private fun initViews() {
        fFmpeg = FFmpeg.getInstance(requireContext())
        progressDialog = ProgressDialog(requireContext())
        mStickerBSFragment = StickerBSFragment()
        mStickerBSFragment!!.setStickerListener(this)
        propertiesBSFragment = PropertiesBSFragment()
        propertiesBSFragment!!.setPropertiesChangeListener(this)
        mPhotoEditor = PhotoEditor.Builder(requireContext(), binding!!.ivImage)
            .setPinchTextScalable(true) // set flag to make text scalable when pinch
            .setDeleteView(binding!!.imgDelete) //.setDefaultTextTypeface(mTextRobotoTf)
            //.setDefaultEmojiTypeface(mEmojiTypeFace)
            .build() // build photo editor sdk
        mPhotoEditor?.setOnPhotoEditorListener(this)
        binding!!.backButton.setOnClickListener(this)
        binding!!.imgDone.setOnClickListener(this)
        binding!!.imgDraw.setOnClickListener(this)
        binding!!.imgText.setOnClickListener(this)
        binding!!.imgUndo.setOnClickListener(this)
        binding!!.imgSticker.setOnClickListener(this)
//        binding!!.videoSurface.surfaceTextureListener = object :
//            TextureView.SurfaceTextureListener {
//            override fun onSurfaceTextureAvailable(
//                surfaceTexture: SurfaceTexture,
//                i: Int,
//                i1: Int
//            ) {
//                //                activityHomeBinding.videoSurface.getLayoutParams().height=640;
//                //                activityHomeBinding.videoSurface.getLayoutParams().width=720;
//                val surface: Surface = Surface(surfaceTexture)
//                try {
//                    mediaPlayer = MediaPlayer()
//                    //                    mediaPlayer.setDataSource("http://daily3gp.com/vids/747.3gp");
//                    Log.d("VideoPath>>", (videoPath)!!)
//                    mediaPlayer!!.setDataSource(videoPath)
//                    mediaPlayer!!.setSurface(surface)
//                    mediaPlayer!!.prepare()
//                    mediaPlayer!!.setOnCompletionListener(onCompletionListener)
//                    mediaPlayer!!.setAudioStreamType(AudioManager.STREAM_MUSIC)
//                    mediaPlayer!!.start()
//                } catch (e: IllegalArgumentException) {
//                    // TODO Auto-generated catch block
//                    e.printStackTrace()
//                } catch (e: SecurityException) {
//                    // TODO Auto-generated catch block
//                    e.printStackTrace()
//                } catch (e: IllegalStateException) {
//                    // TODO Auto-generated catch block
//                    e.printStackTrace()
//                } catch (e: IOException) {
//                    // TODO Auto-generated catch block
//                    e.printStackTrace()
//                }
//            }
//
//            override fun onSurfaceTextureSizeChanged(
//                surfaceTexture: SurfaceTexture,
//                i: Int,
//                i1: Int
//            ) {
//            }
//
//            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
//                return false
//            }
//
//            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
//        }
        exeCmd = ArrayList()
        try {
            fFmpeg?.loadBinary(object : FFmpegLoadBinaryResponseHandler {
                override fun onFailure() {
                    Log.d("binaryLoad", "onFailure")
                }

                override fun onSuccess() {
                    Log.d("binaryLoad", "onSuccess")
                }

                override fun onStart() {
                    Log.d("binaryLoad", "onStart")
                }

                override fun onFinish() {
                    Log.d("binaryLoad", "onFinish")
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun executeCommand(command: Array<String?>?, absolutePath: String?) {
        try {
            fFmpeg!!.execute(command, object : FFmpegExecuteResponseHandler {
                override fun onSuccess(s: String) {
                    Log.d("CommandExecute", "onSuccess  $s")
                    Toast.makeText(requireContext(), "Sucess", Toast.LENGTH_SHORT).show()
                    val i = Intent(requireContext(), VideoPreviewActivity::class.java)
                    i.putExtra("DATA", absolutePath)
                    startActivity(i)
                }

                override fun onProgress(s: String) {
                    progressDialog!!.setMessage(s)
                    Log.d("CommandExecute", "onProgress  $s")
                }

                override fun onFailure(s: String) {
                    Log.d("CommandExecute", "onFailure  $s")
                    progressDialog!!.hide()
                }

                override fun onStart() {
                    progressDialog!!.setTitle("Preccesing")
                    progressDialog!!.setMessage("Starting")
                    progressDialog!!.show()
                }

                override fun onFinish() {
                    progressDialog!!.hide()
                }
            })
        } catch (e: FFmpegCommandAlreadyRunningException) {
            e.printStackTrace()
        }
    }

    override fun onClick(v: View) {
        Log.e(TAG,v.id.toString())
        when (v.id) {
            R.id.imgClose -> {Navigation.findNavController(requireActivity(), R.id.container).navigateUp()}
            R.id.imgDone -> saveImage()
            R.id.imgDraw -> setDrawingMode()
            R.id.imgText -> {
                val textEditorDialogFragment = TextEditorDialogFragment.show(requireActivity() as AppCompatActivity, 0)
                textEditorDialogFragment.setOnTextEditorListener { inputText, colorCode, position ->
                    val styleBuilder = TextStyleBuilder()
                    styleBuilder.withTextColor(colorCode)
                    val typeface = ResourcesCompat.getFont(
                        requireContext(),
                        TextEditorDialogFragment.getDefaultFontIds(requireContext())[position]
                    )
                    styleBuilder.withTextFont((typeface)!!)
                    mPhotoEditor!!.addText(inputText, styleBuilder, position)
                }
            }
            R.id.imgUndo -> {
                Log.d("canvas>>", mPhotoEditor!!.undoCanvas().toString() + "")
                mPhotoEditor!!.clearBrushAllViews()
            }
            R.id.imgSticker -> mStickerBSFragment!!.show(
                childFragmentManager,
                mStickerBSFragment!!.tag
            )
        }
    }

    private fun setCanvasAspectRatio() {
        originalDisplayHeight = displayHeight
        originalDisplayWidth = displayWidth
        val displayDiamenion = Utils.getScaledDimension(
            DimensionData(
                DRAW_CANVASW, DRAW_CANVASH
            ),
            DimensionData(originalDisplayWidth, originalDisplayHeight)
        )
        newCanvasWidth = displayDiamenion.width
        newCanvasHeight = displayDiamenion.height
    }

    private fun setDrawingMode() {
        if (mPhotoEditor!!.brushDrawableMode) {
            mPhotoEditor!!.setBrushDrawingMode(false)
            binding!!.imgDraw.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.black_trasp))
        } else {
            mPhotoEditor!!.setBrushDrawingMode(true)
            binding!!.imgDraw.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary))
            propertiesBSFragment!!.show(childFragmentManager, propertiesBSFragment!!.tag)
        }
    }

    @SuppressLint("MissingPermission")
    private fun saveImage() {
        val file = File(
            requireActivity().filesDir
                .toString() + File.separator + ""
                    + System.currentTimeMillis() + ".png"
        )
        try {
            file.createNewFile()
            val saveSettings = SaveSettings.Builder()
                .setClearViewsEnabled(true)
                .setTransparencyEnabled(false)
                .build()
            mPhotoEditor!!.saveAsFile(file.absolutePath, saveSettings, object :
                PhotoEditor.OnSaveListener {
                override fun onSuccess(imagePath: String) {
                    this@VideoViewerFragment.imagePath = imagePath
                    Log.d("imagePath>>", imagePath)
                    Log.d("imagePath2>>", Uri.fromFile(File(imagePath)).toString())
                    binding!!.ivImage.source.setImageURI(Uri.fromFile(File(imagePath)))
                    Toast.makeText(
                        requireContext(),
                        "Saved successfully...",
                        Toast.LENGTH_SHORT
                    ).show()
                    applayWaterMark()
                }

                override fun onFailure(exception: Exception) {
                    Toast.makeText(
                        requireContext(),
                        "Saving Failed...",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun applayWaterMark() {
        val output = File(
            (requireActivity().filesDir
                .toString() + File.separator + ""
                    + System.currentTimeMillis() + ".mp4")
        )
        try {
            output.createNewFile()
            exeCmd!!.add("-y")
            exeCmd!!.add("-i")
            exeCmd!!.add(videoPath)
            exeCmd!!.add("-i")
            exeCmd!!.add(imagePath)
            exeCmd!!.add("-filter_complex")
            exeCmd!!.add("[1:v]scale=$DRAW_CANVASW:$DRAW_CANVASH[ovrl];[0:v][ovrl]overlay=x=0:y=0")
            exeCmd!!.add("-c:v")
            exeCmd!!.add("libx264")
            exeCmd!!.add("-preset")
            exeCmd!!.add("ultrafast")
            exeCmd!!.add(output.absolutePath)
            newCommand = arrayOfNulls(exeCmd!!.size)
            for (j in exeCmd!!.indices) {
                newCommand!![j] = exeCmd!![j]
            }
            for (k in newCommand?.indices!!) {
                Log.d("CMD==>>", newCommand!![k] + "")
            }

//            newCommand = new String[]{"-i", videoPath, "-i", imagePath, "-preset", "ultrafast", "-filter_complex", "[1:v]scale=2*trunc(" + (width / 2) + "):2*trunc(" + (height/ 2) + ") [ovrl], [0:v][ovrl]overlay=0:0" , output.getAbsolutePath()};
            executeCommand(newCommand, output.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStickerClick(bitmap: Bitmap) {
        mPhotoEditor!!.setBrushDrawingMode(false)
        binding!!.imgDraw.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.black_trasp))
        mPhotoEditor!!.addImage(bitmap)
    }

    override fun onEditTextChangeListener(
        rootView: View,
        text: String,
        colorCode: Int,
        position: Int
    ) {
        val textEditorDialogFragment =
            TextEditorDialogFragment.show(requireActivity() as AppCompatActivity, text, colorCode, position)
        textEditorDialogFragment.setOnTextEditorListener { inputText, colorCode, position ->
            val styleBuilder = TextStyleBuilder()
            styleBuilder.withTextColor(colorCode)
            val typeface = ResourcesCompat.getFont(
                requireContext(),
                TextEditorDialogFragment.getDefaultFontIds(requireContext())[position]
            )
            styleBuilder.withTextFont((typeface)!!)
            mPhotoEditor!!.editText(rootView, inputText, styleBuilder, position)
        }
    }

    fun generatePath(uri: Uri, context: Context): String? {
        var filePath: String? = null
        val isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
        if (isKitKat) {
            filePath = generateFromKitkat(uri, context)
        }
        if (filePath != null) {
            return filePath
        }
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null
        )
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                filePath = cursor.getString(columnIndex)
            }
            cursor.close()
        }
        return filePath ?: uri.path
    }

    @TargetApi(19)
    private fun generateFromKitkat(uri: Uri, context: Context): String? {
        var filePath: String? = null
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val wholeID = DocumentsContract.getDocumentId(uri)
            val id = wholeID.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1]
            val column = arrayOf(MediaStore.Video.Media.DATA)
            val sel = MediaStore.Video.Media._ID + "=?"
            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                column, sel, arrayOf(id), null
            )
            val columnIndex = cursor!!.getColumnIndex(column[0])
            if (cursor.moveToFirst()) {
                filePath = cursor.getString(columnIndex)
            }
            cursor.close()
        }
        return filePath
    }

    private val displayWidth: Int
        private get() {
            val displayMetrics = DisplayMetrics()
            requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
            return displayMetrics.widthPixels
        }
    private val displayHeight: Int
        private get() {
            val displayMetrics = DisplayMetrics()
            requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
            return displayMetrics.heightPixels
        }

    override fun onAddViewListener(viewType: ViewType, numberOfAddedViews: Int) {
        Log.d(
            TAG,
            "onAddViewListener() called with: viewType = [$viewType], numberOfAddedViews = [$numberOfAddedViews]"
        )
    }

    override fun onRemoveViewListener(viewType: ViewType, numberOfAddedViews: Int) {
        Log.d(
            TAG,
            "onRemoveViewListener() called with: viewType = [$viewType], numberOfAddedViews = [$numberOfAddedViews]"
        )
    }

    override fun onStartViewChangeListener(viewType: ViewType) {
        Log.d(
            TAG,
            "onStartViewChangeListener() called with: viewType = [$viewType]"
        )
    }

    override fun onStopViewChangeListener(viewType: ViewType) {
        Log.d(
            TAG,
            "onStopViewChangeListener() called with: viewType = [$viewType]"
        )
    }

    override fun onColorChanged(colorCode: Int) {
        mPhotoEditor!!.brushColor = colorCode
    }

    override fun onOpacityChanged(opacity: Int) {}
    override fun onBrushSizeChanged(brushSize: Int) {}

    companion object {
        private val TAG = PreviewVideoActivity::class.java.simpleName
        private val CAMERA_REQUEST = 52
        private val PICK_REQUEST = 53
    }
}
