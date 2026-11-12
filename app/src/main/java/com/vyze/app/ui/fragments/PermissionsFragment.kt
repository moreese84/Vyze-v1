/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.vyze.app.ui.fragments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.vyze.app.R

private val PERMISSIONS_REQUIRED = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)

/**
 * Requests permissions and, once granted, displays the camera fragment.
 *
 * LOOP BREAK (audit P2 #14, 2026-11-09): this fragment used to navigate
 * to camera on EVERY result — granted or denied — while CameraFragment
 * bounces back here from onResume whenever permissions are missing. Any
 * denial (dialog or never-ask-again) spun an endless camera ⇄
 * permissions swap. Denials now STAY on a recovery screen (Retry +
 * Open Settings) instead of forwarding; only a grant proceeds.
 *
 * On Android 11+ (API 30+), also checks [Environment.isExternalStorageManager()]
 * for `MANAGE_EXTERNAL_STORAGE`. This is non-blocking — if the user denies it,
 * we still navigate to camera but the model must be loaded from bundled assets.
 */
class PermissionsFragment : Fragment() {

    private var statusText: TextView? = null

    /** True once a permission result arrived (distinguishes first-run from denial). */
    private var hasAskedOnce = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            hasAskedOnce = true
            val denied = permissions.filter { !it.value }.keys.toList()
            if (denied.isEmpty()) {
                Toast.makeText(
                    context,
                    "All permissions granted",
                    Toast.LENGTH_LONG
                ).show()
                checkAndRequestStoragePermission()
            } else {
                // STAY — forwarding to camera re-bounces here (#14).
                updateDenialUi(denied)
            }
        }

    /**
     * Launcher for the "All files access" settings page on Android 11+.
     * Non-blocking: navigates to camera regardless of result.
     */
    private val storageSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // User returned from Settings — check result and navigate
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
            ) {
                Toast.makeText(context, "Storage access granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    context,
                    "Storage access not granted — model will load from bundled assets",
                    Toast.LENGTH_LONG
                ).show()
            }
            navigateToCamera()
        }

    /**
     * Launcher for the app-details Settings page (permanent denial
     * recovery). On return, granted proceeds; denied stays put.
     */
    private val appSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (!isAdded) return@registerForActivityResult
            if (hasPermissions(requireContext())) {
                checkAndRequestStoragePermission()
            } else {
                updateDenialUi(missingPermissions())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasPermissions(requireContext())) {
            // Camera/audio permissions already granted — check storage next
            checkAndRequestStoragePermission()
        } else {
            requestPermissionLauncher.launch(missingPermissions().toTypedArray())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Headless fast path when nothing is missing (no screen flash);
        // otherwise the denial recovery screen.
        if (context == null || hasPermissions(requireContext())) return null
        return inflater.inflate(R.layout.fragment_permissions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusText = view.findViewById(R.id.text_permissions_status)
        view.findViewById<Button>(R.id.btn_permissions_retry).setOnClickListener {
            requestPermissionLauncher.launch(missingPermissions().toTypedArray())
        }
        view.findViewById<Button>(R.id.btn_permissions_settings).setOnClickListener {
            openAppSettings()
        }
        updateDenialUi(missingPermissions())
    }

    override fun onDestroyView() {
        statusText = null
        super.onDestroyView()
    }

    private fun missingPermissions(): List<String> = PERMISSIONS_REQUIRED.filter {
        ContextCompat.checkSelfPermission(
            requireContext(),
            it
        ) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * Show denial guidance: rationale text while the system would still
     * show a dialog (first denial), Settings guidance once a denial is
     * permanent (never-ask-again: no rationale + still denied after a
     * result arrived).
     */
    private fun updateDenialUi(denied: List<String>) {
        val ctx = context ?: return
        val permanent = hasAskedOnce && denied.any { !shouldShowRequestPermissionRationale(it) }
        val text = if (permanent) {
            ctx.getString(R.string.perms_guidance_permanent)
        } else {
            ctx.getString(R.string.perms_guidance_rationale)
        }
        statusText?.text = text
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        }
        appSettingsLauncher.launch(intent)
    }

    /**
     * On Android 11+ (API 30+), check if `MANAGE_EXTERNAL_STORAGE` is granted.
     * If not, open the system settings page so the user can enable it.
     * This is non-blocking — we navigate to camera either way.
     */
    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(
                    requireContext(),
                    "For VLM model loading from Downloads, grant 'All files access' in the next screen",
                    Toast.LENGTH_LONG
                ).show()

                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${requireContext().packageName}")
                }
                storageSettingsLauncher.launch(intent)
                return
            }
        }
        // Android 10 and below, or already granted — proceed directly
        navigateToCamera()
    }

    private fun navigateToCamera() {
        lifecycleScope.launchWhenStarted {
            Navigation.findNavController(
                requireActivity(),
                R.id.fragment_container
            )
                .navigate(PermissionsFragmentDirections.actionPermissionsToCamera())
        }
    }

    companion object {

        /** Convenience method used to check if all permissions required by this app are granted */
        fun hasPermissions(context: Context) =
            PERMISSIONS_REQUIRED.all {
                ContextCompat.checkSelfPermission(
                    context,
                    it
                ) == PackageManager.PERMISSION_GRANTED
            }
    }
}
