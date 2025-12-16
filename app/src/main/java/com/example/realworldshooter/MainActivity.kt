package com.example.realworldshooter

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.realworldshooter.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // HOST
        binding.hostCard.setOnClickListener {
            val intent = Intent(this, GameActivity::class.java)
            intent.putExtra("mode", "host")
            startActivity(intent)
        }

        // JOIN
        binding.joinCard.setOnClickListener {
            val intent = Intent(this, GameActivity::class.java)
            intent.putExtra("mode", "join")
            startActivity(intent)
        }

        // EXIT
        binding.exitBar.setOnClickListener {
            finish()
        }
    }
}
