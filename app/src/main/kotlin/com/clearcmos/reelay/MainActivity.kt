package com.clearcmos.reelay

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/** Launcher screen: explains the share-sheet flow and lets a pasted link exercise it. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val input = findViewById<EditText>(R.id.link)
        findViewById<Button>(R.id.send).setOnClickListener {
            startActivity(
                Intent(this, ShareActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, input.text?.toString().orEmpty())
            )
        }
    }
}
