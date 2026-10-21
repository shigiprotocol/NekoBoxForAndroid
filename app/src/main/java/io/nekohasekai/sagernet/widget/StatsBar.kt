package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var behavior: YourBehavior

    var allowShow = true

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }
    }


    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    // ShigiProtocol: avoid overlapping tests and drop results from an old session
    @Volatile
    private var testing = false
    private var session = 0

    fun changeState(state: BaseService.State) {
        val activity = context as MainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        session++
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            val mySession = session
            postWhenStarted {
                if (allowShow) performShow()
                setStatus(app.getText(R.string.vpn_connected))
            }
            // auto run the connection test once after connecting
            activity.lifecycleScope.launch(Dispatchers.Main) {
                delay(800L)
                if (mySession == session && DataStore.serviceState.connected) testConnection()
            }
        } else {
            postWhenStarted {
                performHide()
            }
            updateSpeed(0, 0, 0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    private val activeColor = Color.parseColor("#FF8C00") // orange, same as the app icon
    private val idleColor = Color.parseColor("#9E9E9E")

    private fun speedLine(arrow: String, rate: Long, total: Long): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(arrow)
        // arrow is orange while data is flowing, grey when idle
        sb.setSpan(
            ForegroundColorSpan(if (rate > 0) activeColor else idleColor),
            0, arrow.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.append("  ")
        sb.append(context.getString(R.string.speed, Formatter.formatFileSize(context, rate)))
        if (total > 0) {
            sb.append("   (")
            sb.append(Formatter.formatFileSize(context, total))
            sb.append(")")
        }
        return sb
    }

    fun updateSpeed(txRate: Long, rxRate: Long, txTotal: Long = 0L, rxTotal: Long = 0L) {
        if (!this::txText.isInitialized) return
        txText.text = speedLine("▲", txRate, txTotal)
        rxText.text = speedLine("▼", rxRate, rxTotal)
    }

    private fun flagOf(countryCode: String): String {
        val cc = countryCode.trim().uppercase()
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🌐"
        val first = Character.codePointAt(cc, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(cc, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    @SuppressLint("SetTextI18n")
    fun testConnection() {
        if (testing) return
        val activity = context as MainActivity
        val mySession = session
        testing = true
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                val pingText = app.getString(R.string.shigi_ping, elapsed)
                onMainDispatcher {
                    if (mySession == session) setStatus(pingText + "   •   " + app.getString(R.string.shigi_getting_ip))
                }
                // exit IP & country, through the active outbound
                val info = try {
                    activity.queryExitInfo()
                } catch (e: Exception) {
                    Logs.w(e.toString())
                    null
                }
                onMainDispatcher {
                    isEnabled = true
                    testing = false
                    if (mySession != session) return@onMainDispatcher
                    if (info.isNullOrBlank()) {
                        setStatus(pingText)
                    } else {
                        val ip = info.substringBefore("|")
                        val cc = info.substringAfter("|", "")
                        val loc = if (cc.isNotBlank()) "${flagOf(cc)} $cc" else flagOf(cc)
                        setStatus("$loc   $ip   •   $pingText")
                    }
                }
            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    testing = false
                    if (mySession != session) return@onMainDispatcher
                    // fix: used to stay stuck on "Testing…" after a failure
                    setStatus(app.getText(R.string.connection_test_fail))

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
