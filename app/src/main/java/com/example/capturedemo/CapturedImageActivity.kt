package com.example.capturedemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.capturedemo.databinding.ActivityCapturedImageBinding

class CapturedImageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCapturedImageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the view binding
        binding = ActivityCapturedImageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imagePath = intent.getStringExtra("image_path")
        if (imagePath != null) {
            val bitmap = BitmapFactory.decodeFile(imagePath)
            binding.capturedImageView.setImageBitmap(bitmap)
        }

        binding.backButton.setOnClickListener {
            finish()
        }
    }
}