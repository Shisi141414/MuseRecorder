package com.neuroskill.muserecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.neuroskill.muserecorder.core.MuseHub
import com.neuroskill.muserecorder.databinding.ActivityMainBinding
import com.neuroskill.muserecorder.ui.ConnectFragment
import com.neuroskill.muserecorder.ui.OverviewFragment
import com.neuroskill.muserecorder.ui.RecordFragment
import com.neuroskill.muserecorder.ui.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 结果由各页面在使用前自行检查 */ }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        MuseHub.init(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            switchTo(OverviewFragment())
            binding.bottomNav.selectedItemId = R.id.nav_overview
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_overview -> switchTo(OverviewFragment())
                R.id.nav_connect -> switchTo(ConnectFragment())
                R.id.nav_record -> switchTo(RecordFragment())
                R.id.nav_settings -> switchTo(SettingsFragment())
            }
            true
        }

        requestBlePermissions()
    }

    private fun switchTo(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .commitAllowingStateLoss()
    }

    /** 跳转到指定底部导航页 */
    fun goToTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    /** 请求蓝牙 / 通知所需权限 */
    fun requestBlePermissions() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            wanted += Manifest.permission.BLUETOOTH_SCAN
            wanted += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
