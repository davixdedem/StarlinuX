package com.magix.pistarlink.ui.home

import kotlin.math.pow
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.magix.pistarlink.OpenWRTApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.io.File
import kotlin.math.log10
import java.io.IOException
import org.json.JSONObject
import org.json.JSONException
import java.io.BufferedReader
import android.widget.Spinner
import com.magix.pistarlink.R
import android.widget.EditText
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import kotlinx.coroutines.launch
import android.widget.AdapterView
import android.widget.ArrayAdapter
import kotlinx.coroutines.isActive
import com.suke.widget.SwitchButton
import android.content.ComponentName
import android.animation.ObjectAnimator
import android.content.ServiceConnection
import com.ncorti.slidetoact.SlideToActView
import de.blinkt.openvpn.api.IOpenVPNAPIService
import android.view.inputmethod.InputMethodManager
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import com.magix.pistarlink.databinding.FragmentHomeBinding
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var webView: WebView

    /*Init*/
    private lateinit var openWRTApi: OpenWRTApi
    private lateinit var luciToken: String
    private lateinit var job: Job
    private var canCallHomeAPi = false
    public var isBoardReachable = false
    private var isLaserAdded = false

    /*Vpn*/
    private var mService: IOpenVPNAPIService? = null
    private var profileUUID: String? = null
    private var isVpnConnected = false
    private var myOpenVPNIPv4Addr = ""
    private val profileName = "Pi-Starlink"
    private val icsOpenvpnPermission: Int = 7
    private val vpnDevices = mutableListOf<DhcpLease>()

    /*Ddns*/
    private var isHostnameEditing : Boolean = false
    private var isUsernameEditing : Boolean = false
    private var isPasswordEditing : Boolean = false
    private var hostnameInitialText : String = ""
    private var usernameInitialText : String = ""
    private var passwordInitialText : String = ""

    /*Define a Data Class for Network Lease*/
    data class DhcpLease(
        val hostname: String,
        val ipaddr: String,
        val macAddr: String,
    )

    /*Define a Data Class for Port Forwarding*/
    data class PortForwardingCard(
        val id: String,
        val title: String,
        val externalPort: String,
        val destinationPort: String,
        val ipv6Target: String,
        val enabled: String
    )

    private val dhcpLeases = mutableListOf<DhcpLease>()
    private val dhcp6Leases = mutableListOf<DhcpLease>()
    private val pfRules = mutableListOf<PortForwardingCard>()

    /*Luci configurations*/
    private val username = "root"
    private val password = "t*iP9Tk6na3VPeq"
    private val baseUrl = "http://192.168.1.1"
    private val systemBoardCommand = "ubus call system board"
    private val systemBoardInformationCommand = "ubus call system info"
    private val getIPv6addressCommand =
        "ip -6 addr show dev eth0 | grep inet6 | awk '{ print \$2 }' | awk -F'/' '{ print \$1 }' | grep '^2a0d' | head -n 1; ip -4 addr show dev eth0 | grep inet | awk '{ print \$2 }' | awk -F'/' '{ print \$1 }' | head -n 1"
    private val getConnectedDevices = "ubus call luci-rpc getDHCPLeases"
    private val getOpenVPNConnectedDevicesCommand = "bash /root/scripts/openvpn_devices.sh"
    private val setupVPNCommand = "bash /root/scripts/openvpn_configure_callback.sh"
    private val checkVPNConfigStatusCommand = "cat /root/scripts/vpn_config_status"
    private val fetchVPNConfigurationFileCommand = "cat /etc/openvpn/"
    private val setConfigAsFetched = "echo '0' > /root/scripts/vpn_config_status "
    private val getDDNSConfig =
        "echo \\\"{\\\\\\\"lookup_host\\\\\\\":\\\\\\\"\$(uci get ddns.myddns_ipv6.lookup_host)\\\\\\\", \\\\\\\"username\\\\\\\":\\\\\\\"\$(uci get ddns.myddns_ipv6.username)\\\\\\\", \\\\\\\"password\\\\\\\":\\\\\\\"\$(uci get ddns.myddns_ipv6.password)\\\\\\\"}\\\""
    private val setDDNSHostname = "uci set ddns.myddns_ipv6.lookup_host="
    private val setDDNSDomain = "uci set ddns.myddns_ipv6.domain="
    private val setDDNSUsername = "uci set ddns.myddns_ipv6.username="
    private val setDDNSPassword = "uci set ddns.myddns_ipv6.password="
    private val commitDDNSCommand  = "uci commit ddns"
    private val getRedirectFirewallRulesCommand  = "bash /root/scripts/port_forwarding_configurations.sh"
    private val setPortForwardingNameCommand = "uci set firewall.@redirect['%s'].name='%s'"
    private val setPortForwardingExternalPortCommand = "uci set firewall.@redirect['%s'].src_dport='%s'"
    private val setPortForwardingDestinationPortCommand = "uci set firewall.@redirect['%s'].dest_port='%s'"
    private val setPortForwardingTargetIPCommand = "uci set firewall.@redirect['%s'].dest_ip='%s'"
    private val setPortForwardingEnabledCommand = "uci set firewall.@redirect['%s'].enabled='%s'"
    private val commitFirewallCommand  = "uci commit firewall"

    /*Configurations*/
    private val onlineStatus = "Online"
    private val offlineStatus = "Disconnected"
    private val openWRTTag = "OpenWRT"
    private lateinit var blinkAnimator: BlinkAnimator

    override fun onStart() {
        super.onStart()

        /*Binding OpenVPN For Android service, this check permissions too */
        bindService()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        /*Init the OpenWRTApi class*/
        openWRTApi = OpenWRTApi(baseUrl, username, password)

        /*Try to login to Luci on time*/
        performLoginAndUpdate(true)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        /** START - WEB VIEW**/
        webView = view.findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            allowUniversalAccessFromFileURLs = true
        }
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/index.html")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        val jsInterface = context?.let { JavaScriptInterface(it, this) }
        /*Connect the Javascript interface to the Web view*/
        if (jsInterface != null) {
            webView.addJavascriptInterface(jsInterface, "AndroidFunction")
        }
        /** END - WEB VIEW**/

        /*Binding Raspberry Pi info buttons*/
        binding.sbcTextName.setOnClickListener {
            if (isBoardReachable) {
                /*Call the OpenWRT Api in order to popule the system board information*/
                callOpenWRTApi()

                /*Call the OpenWRT Api in order to popule the system storage information*/
                callOpenWRTStorageApi()

                /*Handle its view visibility*/
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentSystemIncluded.root.visibility = View.VISIBLE
            }
        }
        binding.sbcBtnInfo.setOnClickListener {
            /*Handle its view visibility*/
            binding.homeMainLayout.visibility = View.GONE
            binding.fragmentSystemIncluded.root.visibility = View.VISIBLE
        }

        /*** START - SYSTEM INFORMATION FRAGMENT ***/
        /*Applying effect at Raspberry Pi buttons*/
        binding.sbcTextName.setOnTouchListener { view, motionEvent ->
            if (isBoardReachable) {
                when (motionEvent.action) {
                    MotionEvent.ACTION_DOWN -> {
                        /*Apply opacity effect when pressed*/
                        view.alpha = 0.5f
                    }

                    MotionEvent.ACTION_UP -> {
                        /*Revert back to original opacity*/
                        view.alpha = 1.0f
                        /*Call performClick to trigger the click event*/
                        view.performClick()
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        /*Revert back to original opacity if the action was canceled*/
                        view.alpha = 1.0f
                    }
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }
        binding.sbcBtnInfo.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    /*Apply opacity effect when pressed*/
                    view.alpha = 0.5f
                }

                MotionEvent.ACTION_UP -> {
                    /*Revert back to original opacity*/
                    view.alpha = 1.0f
                    /*Call performClick to trigger the click event*/
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    /*Revert back to original opacity if the action was canceled*/
                    view.alpha = 1.0f
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }

        /*Binding system info exit info button*/
        binding.fragmentSystemIncluded.exitImage.setOnClickListener {
            binding.homeMainLayout.visibility = View.VISIBLE
            binding.fragmentSystemIncluded.root.visibility = View.GONE
        }

        /*Applying effect at system info exit button*/
        binding.fragmentSystemIncluded.exitImage.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    /*Apply opacity effect when pressed*/
                    view.alpha = 0.5f
                }

                MotionEvent.ACTION_UP -> {
                    /*Revert back to original opacity*/
                    view.alpha = 1.0f
                    /*Call performClick to trigger the click event*/
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    /*Revert back to original opacity if the action was canceled*/
                    view.alpha = 1.0f
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }
        /*** END - SYSTEM INFORMATION FRAGMENT ***/

        /*** START - NETWORK FRAGMENT ***/
        /*Applying effect at Network buttons*/
        binding.networkText.setOnTouchListener { view, motionEvent ->
            if (isBoardReachable) {
                when (motionEvent.action) {
                    MotionEvent.ACTION_DOWN -> {
                        /*Apply opacity effect when pressed*/
                        view.alpha = 0.5f
                    }

                    MotionEvent.ACTION_UP -> {
                        /*Revert back to original opacity*/
                        view.alpha = 1.0f
                        /*Call performClick to trigger the click event*/
                        view.performClick()
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        /*Revert back to original opacity if the action was canceled*/
                        view.alpha = 1.0f
                    }
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }
        binding.networkBtn.setOnTouchListener { view, motionEvent ->
            if (isBoardReachable) {
                when (motionEvent.action) {
                    MotionEvent.ACTION_DOWN -> {
                        /*Apply opacity effect when pressed*/
                        view.alpha = 0.5f
                    }

                    MotionEvent.ACTION_UP -> {
                        /*Revert back to original opacity*/
                        view.alpha = 1.0f
                        /*Call performClick to trigger the click event*/
                        view.performClick()
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        /*Revert back to original opacity if the action was canceled*/
                        view.alpha = 1.0f
                    }
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }

        /*Binding Network buttons*/
        binding.networkText.setOnClickListener {
            if (isBoardReachable) {
                /*Handle its view visibility*/
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentNetworkIncluded.root.visibility = View.VISIBLE

                /*Pause main call API*/
                canCallHomeAPi = false

                callOpenWRTNetwork { jsonObject ->
                    jsonObject?.let {
                        // Handle the JSON data
                        Log.d("OpenWRTNetwork", it.toString())
                    } ?: run {
                        // Handle the error case
                        Log.e("OpenWRTNetwork", "Failed to retrieve data")
                    }
                }

            }
        }
        binding.networkBtn.setOnClickListener {
            if (isBoardReachable) {
                /*Handle its view visibility*/
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentNetworkIncluded.root.visibility = View.VISIBLE
            }
        }

        /*Binding Network exit info button*/
        binding.fragmentNetworkIncluded.exitImage.setOnClickListener {
            binding.homeMainLayout.visibility = View.VISIBLE
            binding.fragmentNetworkIncluded.root.visibility = View.GONE

            /*Resume main call API*/
            canCallHomeAPi = true
        }

        /*Applying effect at Network exit button*/
        binding.fragmentNetworkIncluded.exitImage.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    /*Apply opacity effect when pressed*/
                    view.alpha = 0.5f
                }

                MotionEvent.ACTION_UP -> {
                    /*Revert back to original opacity*/
                    view.alpha = 1.0f
                    /*Call performClick to trigger the click event*/
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    /*Revert back to original opacity if the action was canceled*/
                    view.alpha = 1.0f
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }
        /*** END -NETWORK FRAGMENT ***/

        /*** START - VPN FRAGMENT ***/
        /*Applying effect at Vpn buttons*/
        binding.vpnSectionText.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    /*Apply opacity effect when pressed*/
                    view.alpha = 0.5f
                }

                MotionEvent.ACTION_UP -> {
                    /*Revert back to original opacity*/
                    view.alpha = 1.0f
                    /*Call performClick to trigger the click event*/
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    /*Revert back to original opacity if the action was canceled*/
                    view.alpha = 1.0f
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }
        binding.vpnSectionBtn.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    /*Apply opacity effect when pressed*/
                    view.alpha = 0.5f
                }

                MotionEvent.ACTION_UP -> {
                    /*Revert back to original opacity*/
                    view.alpha = 1.0f
                    /*Call performClick to trigger the click event*/
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    /*Revert back to original opacity if the action was canceled*/
                    view.alpha = 1.0f
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }

        /*Binding Vpn buttons*/
        binding.vpnSectionText.setOnClickListener {
            /*Handle its view visibility*/
            binding.homeMainLayout.visibility = View.GONE
            binding.fragmentVpnIncluded.root.visibility = View.VISIBLE

            /*Pause main call API*/
            canCallHomeAPi = false

            updateVPNLayout()

            getOpenVPNConnectedDevices()
        }
        binding.vpnSectionBtn.setOnClickListener {
            /*Handle its view visibility*/
            binding.homeMainLayout.visibility = View.GONE
            binding.fragmentVpnIncluded.root.visibility = View.VISIBLE

            /*Pause main call API*/
            canCallHomeAPi = false

            callOpenWRTNetwork { jsonObject ->
                jsonObject?.let {
                    // Handle the JSON data
                    Log.d("OpenWRTNetwork", it.toString())
                } ?: run {
                    // Handle the error case
                    Log.e("OpenWRTNetwork", "Failed to retrieve data")
                }
            }

            updateVPNLayout()

            getOpenVPNConnectedDevices()
        }

        /*Binding Vpn exit info button*/
        binding.fragmentVpnIncluded.exitImage.setOnClickListener {
            binding.homeMainLayout.visibility = View.VISIBLE
            binding.fragmentVpnIncluded.root.visibility = View.GONE

            /*Pause main call API*/
            canCallHomeAPi = true

            /*Populating home resources*/
            performLoginAndUpdate()
        }

        /*Applying effect at Vpn exit button*/
        binding.fragmentVpnIncluded.exitImage.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    /*Apply opacity effect when pressed*/
                    view.alpha = 0.5f
                }

                MotionEvent.ACTION_UP -> {
                    /*Revert back to original opacity*/
                    view.alpha = 1.0f
                    /*Call performClick to trigger the click event*/
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    /*Revert back to original opacity if the action was canceled*/
                    view.alpha = 1.0f
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }

        /*Binding configuration slide button*/
        binding.fragmentVpnIncluded.vpnConfigActiveSlide.onSlideCompleteListener =
            object : SlideToActView.OnSlideCompleteListener {
                override fun onSlideComplete(view: SlideToActView) {
                    Log.d("OpenVPN", "Configuration slide completed")
                    if (isBoardReachable) {
                        setupVPN()
                    } else {
                        binding.fragmentVpnIncluded.vpnConfigStatus.text =
                            "Pi-Starlink is unreachable. Please connect to its Wi-Fi."
                        binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
                    }
                }
            }

        /*Binding activation slide button*/
        binding.fragmentVpnIncluded.vpnActiveSlide.onSlideCompleteListener =
            object : SlideToActView.OnSlideCompleteListener {
                override fun onSlideComplete(view: SlideToActView) {
                    Log.d("OpenVPN", "Slide activation completed: $profileUUID")
                    if (!isVpnConnected) {
                        profileUUID?.let { connectVPN(it) }
                    } else {
                        disconnectVPN()
                    }
                }
            }
        /*** END - VPN FRAGMENT ***/

        /*** START - DDNS FRAGMENT ***/
        /*Applying effect to DNS buttons*/
        binding.ddnsSectionText.setOnTouchListener { view, motionEvent ->
            if (isBoardReachable) {
                when (motionEvent.action) {
                    MotionEvent.ACTION_DOWN -> {
                        /*Apply opacity effect when pressed*/
                        view.alpha = 0.5f
                    }

                    MotionEvent.ACTION_UP -> {
                        /*Revert back to original opacity*/
                        view.alpha = 1.0f
                        /*Call performClick to trigger the click event*/
                        view.performClick()
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        /*Revert back to original opacity if the action was canceled*/
                        view.alpha = 1.0f
                    }
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }
        binding.ddnsSectionBtn.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    /*Apply opacity effect when pressed*/
                    view.alpha = 0.5f
                }

                MotionEvent.ACTION_UP -> {
                    /*Revert back to original opacity*/
                    view.alpha = 1.0f
                    /*Call performClick to trigger the click event*/
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    /*Revert back to original opacity if the action was canceled*/
                    view.alpha = 1.0f
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }

        /*Binding DNS buttons*/
        binding.ddnsSectionText.setOnClickListener {
            if (isBoardReachable) {
                /*Handle its view visibility*/
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentDdnsIncluded.root.visibility = View.VISIBLE

                /*Pause main call API*/
                canCallHomeAPi = false

                /*Update resources*/
                getDDNSConfiguration()
            }
        }
        binding.ddnsSectionBtn.setOnClickListener {
            if (isBoardReachable) {
                /*Handle its view visibility*/
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentDdnsIncluded.root.visibility = View.VISIBLE

                /*Pause main call API*/
                canCallHomeAPi = false

                /*Update resources*/
                getDDNSConfiguration()
            }
        }

        /*Binding DDNS exit info button*/
        binding.fragmentDdnsIncluded.exitImage.setOnClickListener {
            binding.homeMainLayout.visibility = View.VISIBLE
            binding.fragmentDdnsIncluded.root.visibility = View.GONE

            /*Pause main call API*/
            canCallHomeAPi = true

            /*Populating home resources*/
            performLoginAndUpdate()
        }

        /*Applying effect at Vpn exit button*/
        binding.fragmentDdnsIncluded.exitImage.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    /*Apply opacity effect when pressed*/
                    view.alpha = 0.5f
                }

                MotionEvent.ACTION_UP -> {
                    /*Revert back to original opacity*/
                    view.alpha = 1.0f
                    /*Call performClick to trigger the click event*/
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    /*Revert back to original opacity if the action was canceled*/
                    view.alpha = 1.0f
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }

        /*Binding DDNS hostname pencil, check and close button*/
        val hostnameEditText = binding.fragmentDdnsIncluded.cardLayoutHostname.cardDescription
        binding.fragmentDdnsIncluded.cardLayoutHostname.editValueImage.setOnClickListener {

            if (isHostnameEditing) {
                /*Let the close button be visible*/
                binding.fragmentDdnsIncluded.cardLayoutHostname.closeValueImage.visibility = View.GONE

                // When editing is complete
                hostnameEditText.isFocusable = false
                hostnameEditText.isFocusableInTouchMode = false
                hostnameEditText.isClickable = false
                hostnameEditText.clearFocus()

                // Hide the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(hostnameEditText.windowToken, 0)

                // Change the icon to pencil
                binding.fragmentDdnsIncluded.cardLayoutHostname.editValueImage.setImageResource(R.drawable.pen)

                /*Sync the new hostname with the router*/
                setDDNSConfiguration(setDDNSHostname, hostnameEditText.text.toString())
                setDDNSConfiguration(setDDNSDomain, hostnameEditText.text.toString())

                /*Update the initial value*/
                hostnameInitialText = hostnameEditText.text.toString()


            } else {
                /*Let the close button be visible*/
                binding.fragmentDdnsIncluded.cardLayoutHostname.closeValueImage.visibility = View.VISIBLE

                // When editing is enabled
                hostnameEditText.isFocusable = true
                hostnameEditText.isFocusableInTouchMode = true
                hostnameEditText.isClickable = true
                hostnameEditText.requestFocus()

                // Optionally, show the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(hostnameEditText, InputMethodManager.SHOW_IMPLICIT)

                // Change the icon to check
                binding.fragmentDdnsIncluded.cardLayoutHostname.editValueImage.setImageResource(R.drawable.check)
            }

            // Toggle the state
            isHostnameEditing = !isHostnameEditing
        }
        binding.fragmentDdnsIncluded.cardLayoutHostname.closeValueImage.setOnClickListener {
            hostnameEditText.setText(hostnameInitialText)
            /*Let the close button be visible*/
            binding.fragmentDdnsIncluded.cardLayoutHostname.closeValueImage.visibility = View.GONE

            // When editing is complete
            hostnameEditText.isFocusable = false
            hostnameEditText.isFocusableInTouchMode = false
            hostnameEditText.isClickable = false
            hostnameEditText.clearFocus()

            // Hide the keyboard
            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(hostnameEditText.windowToken, 0)

            // Change the icon to pencil
            binding.fragmentDdnsIncluded.cardLayoutHostname.editValueImage.setImageResource(R.drawable.pen)

            isHostnameEditing = false
        }

        /*Binding DDNS username pencil, check and close button*/
        val usernameEditText = binding.fragmentDdnsIncluded.cardLayoutUsername.cardDescription
        binding.fragmentDdnsIncluded.cardLayoutUsername.editValueImage.setOnClickListener {

            if (isUsernameEditing) {
                /*Let the close button be visible*/
                binding.fragmentDdnsIncluded.cardLayoutUsername.closeValueImage.visibility = View.GONE

                // When editing is complete
                usernameEditText.isFocusable = false
                usernameEditText.isFocusableInTouchMode = false
                usernameEditText.isClickable = false
                usernameEditText.clearFocus()

                // Hide the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(usernameEditText.windowToken, 0)

                // Change the icon to pencil
                binding.fragmentDdnsIncluded.cardLayoutUsername.editValueImage.setImageResource(R.drawable.pen)

                /*Sync the new hostname with the router*/
                setDDNSConfiguration(setDDNSUsername, usernameEditText.text.toString())

                /*Update the initial value*/
                usernameInitialText = usernameEditText.text.toString()


            } else {
                /*Let the close button be visible*/
                binding.fragmentDdnsIncluded.cardLayoutUsername.closeValueImage.visibility = View.VISIBLE

                // When editing is enabled
                usernameEditText.isFocusable = true
                usernameEditText.isFocusableInTouchMode = true
                usernameEditText.isClickable = true
                usernameEditText.requestFocus()

                // Optionally, show the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(usernameEditText, InputMethodManager.SHOW_IMPLICIT)

                // Change the icon to check
                binding.fragmentDdnsIncluded.cardLayoutUsername.editValueImage.setImageResource(R.drawable.check)
            }

            // Toggle the state
            isUsernameEditing = !isUsernameEditing
        }
        binding.fragmentDdnsIncluded.cardLayoutUsername.closeValueImage.setOnClickListener {
            usernameEditText.setText(usernameInitialText)
            /*Let the close button be visible*/
            binding.fragmentDdnsIncluded.cardLayoutUsername.closeValueImage.visibility = View.GONE

            // When editing is complete
            usernameEditText.isFocusable = false
            usernameEditText.isFocusableInTouchMode = false
            usernameEditText.isClickable = false
            usernameEditText.clearFocus()

            // Hide the keyboard
            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(usernameEditText.windowToken, 0)

            // Change the icon to pencil
            binding.fragmentDdnsIncluded.cardLayoutUsername.editValueImage.setImageResource(R.drawable.pen)

            isUsernameEditing = false
        }

        /*Binding DDNS password pencil, check and close button*/
        val passwordEditText = binding.fragmentDdnsIncluded.cardLayoutPassword.cardDescription
        binding.fragmentDdnsIncluded.cardLayoutPassword.editValueImage.setOnClickListener {

            if (isPasswordEditing) {
                /*Let the close button be visible*/
                binding.fragmentDdnsIncluded.cardLayoutPassword.closeValueImage.visibility = View.GONE

                // When editing is complete
                passwordEditText.isFocusable = false
                passwordEditText.isFocusableInTouchMode = false
                passwordEditText.isClickable = false
                passwordEditText.clearFocus()

                /*Hide the keyboard*/
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(passwordEditText.windowToken, 0)

                /*Change the icon to pencil*/
                binding.fragmentDdnsIncluded.cardLayoutPassword.editValueImage.setImageResource(R.drawable.pen)

                /*Sync the new hostname with the router*/
                setDDNSConfiguration(setDDNSPassword, passwordEditText.text.toString())

                /*Update the initial value*/
                passwordInitialText = passwordEditText.text.toString()


            } else {
                /*Let the close button be visible*/
                binding.fragmentDdnsIncluded.cardLayoutPassword.closeValueImage.visibility = View.VISIBLE

                /*When editing is enabled*/
                passwordEditText.isFocusable = true
                passwordEditText.isFocusableInTouchMode = true
                passwordEditText.isClickable = true
                passwordEditText.requestFocus()

                /*Optionally, show the keyboard*/
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(passwordEditText, InputMethodManager.SHOW_IMPLICIT)

                /*Change the icon to check*/
                binding.fragmentDdnsIncluded.cardLayoutPassword.editValueImage.setImageResource(R.drawable.check)
            }

            /*Toggle the state*/
            isPasswordEditing = !isPasswordEditing
        }
        binding.fragmentDdnsIncluded.cardLayoutPassword.closeValueImage.setOnClickListener {
            passwordEditText.setText(passwordInitialText)
            /*Let the close button be visible*/
            binding.fragmentDdnsIncluded.cardLayoutPassword.closeValueImage.visibility = View.GONE

            // When editing is complete
            passwordEditText.isFocusable = false
            passwordEditText.isFocusableInTouchMode = false
            passwordEditText.isClickable = false
            passwordEditText.clearFocus()

            // Hide the keyboard
            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(passwordEditText.windowToken, 0)

            // Change the icon to pencil
            binding.fragmentDdnsIncluded.cardLayoutPassword.editValueImage.setImageResource(R.drawable.pen)

            isPasswordEditing = false
        }
        /*** END - DDNS FRAGMENT ***/

        /*** START - PORT FORWARDING FRAGMENT ***/
        /*Applying effect to PF buttons*/
        binding.pwSectionText.setOnTouchListener { view, motionEvent ->
            if (isBoardReachable) {
                when (motionEvent.action) {
                    MotionEvent.ACTION_DOWN -> {
                        /*Apply opacity effect when pressed*/
                        view.alpha = 0.5f
                    }

                    MotionEvent.ACTION_UP -> {
                        /*Revert back to original opacity*/
                        view.alpha = 1.0f
                        /*Call performClick to trigger the click event*/
                        view.performClick()
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        /*Revert back to original opacity if the action was canceled*/
                        view.alpha = 1.0f
                    }
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }
        binding.pwSectionBtn.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    /*Apply opacity effect when pressed*/
                    view.alpha = 0.5f
                }

                MotionEvent.ACTION_UP -> {
                    /*Revert back to original opacity*/
                    view.alpha = 1.0f
                    /*Call performClick to trigger the click event*/
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    /*Revert back to original opacity if the action was canceled*/
                    view.alpha = 1.0f
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }

        /*Binding PF buttons*/
        binding.pwSectionText.setOnClickListener {
            if (isBoardReachable) {
                /*Handle its view visibility*/
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentPortForwardingIncluded.root.visibility = View.VISIBLE

                /*Pause main call API*/
                canCallHomeAPi = false

                /*Call the API and update resources*/
                callOpenWRTPortForwarding()
            }
        }
        binding.pwSectionBtn.setOnClickListener {
            if (isBoardReachable) {
                /*Handle its view visibility*/
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentPortForwardingIncluded.root.visibility = View.VISIBLE

                /*Pause main call API*/
                canCallHomeAPi = false

                /*Call the API and update resources*/
                callOpenWRTPortForwarding()
            }
        }

        /*Binding PF exit info button*/
        binding.fragmentPortForwardingIncluded.exitImage.setOnClickListener {
            binding.homeMainLayout.visibility = View.VISIBLE
            binding.fragmentPortForwardingIncluded.root.visibility = View.GONE

            /*Pause main call API*/
            canCallHomeAPi = true

        }

        /*Applying effect at Vpn exit button*/
        binding.fragmentPortForwardingIncluded.exitImage.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    /*Apply opacity effect when pressed*/
                    view.alpha = 0.5f
                }

                MotionEvent.ACTION_UP -> {
                    /*Revert back to original opacity*/
                    view.alpha = 1.0f
                    /*Call performClick to trigger the click event*/
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    /*Revert back to original opacity if the action was canceled*/
                    view.alpha = 1.0f
                }
            }
            /*Return true to indicate that the event has been handled*/
            return@setOnTouchListener true
        }
        /*** END - PORT FORWARDING FRAGMENT ***/

        /*Updating resources on time*/
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (canCallHomeAPi) {
                    Log.d("OpenWRT","Calling function from loop")
                    performLoginAndUpdate()
                }
                delay(10000)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null

        /*Disconnecting VPN and unbind its service*/
        disconnectVPN()
        unbindService()
    }

    override fun onPause() {
        super.onPause()

        /*Setting variable in order to stop on time status call*/
        canCallHomeAPi = false
    }

    override fun onResume() {
        super.onResume()

        /*Perform login and update home resources*/
        performLoginAndUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()

        /*Disconnect VPN in case of Destroying*/
        disconnectVPN()
        unbindService()
    }

    /* Performs login in order to retrieve the auth token*/
    private fun performLoginAndUpdate(firstCall: Boolean = false) {
        Log.d("OpenWRT","Performing login and updating resources")
        openWRTApi.login(
            onSuccess = { success ->
                /*Handle successful login, update UI on the main thread*/
                activity?.runOnUiThread {
                    Log.d("OpenWRT", success.toString())

                    /*Update the token*/
                    luciToken = success.get("result").toString()

                    /*luciToken has got, calling the API to get IPv6 address*/
                    callOpenWRTIPApi()

                    /*Update the board status as online*/
                    updateBoardStatus(true)
                    if (firstCall){
                        /*Set variable to true*/
                        canCallHomeAPi = true
                    }

                }
            },
            onFailure = { error ->
                /*Handle login failure, update UI on the main thread*/
                activity?.runOnUiThread {
                    /*Update the board status as disconnected*/
                    updateBoardStatus(false)

                    Log.d("OpenWRT", "OpenWRT Error: $error")
                }
            }
        )
    }

    /*Updates the board status resources*/
    private fun updateBoardStatus(isOnline: Boolean) {
        isBoardReachable = isOnline
        //trigLaserBeam(isOnline)

        val icon: ImageView = binding.boardStatusIcon
        val alphaValue = if (isOnline) 1.0F else 0.5F

        /*Showing the IP addresses layout*/
        binding.layoutIpAddresses.root.visibility = View.VISIBLE

        // Choose the drawable resource based on the status
        val drawableResId = if (isOnline) R.drawable.online_dot else R.drawable.offline_dot
        binding.sbcTextStatus.text = if (isOnline) onlineStatus else offlineStatus
        binding.sbcTextName.alpha = if (isOnline) 1F else 0.5F
        binding.sbcTextName.isClickable = isOnline

        // Update the ImageView
        icon.setImageResource(drawableResId)

        /*DMZ*/
/*        binding.dmzSectionText.alpha = alphaValue
        binding.dmzImageIcon.alpha = alphaValue
        binding.dmzSectionBtn.alpha = alphaValue*/

        /*DDNS*/
        binding.ddnsSectionText.alpha = alphaValue
        binding.ddnsImageIcon.alpha = alphaValue
        binding.ddnsSectionBtn.alpha = alphaValue

        /*Port Forwarding*/
        binding.pwSectionText.alpha = alphaValue
        binding.pwImageIcon.alpha = alphaValue
        binding.pwSectionBtn.alpha = alphaValue

        /*Network*/
        binding.networkText.alpha = alphaValue
        binding.networkImageIcon.alpha = alphaValue
        binding.networkBtn.alpha = alphaValue

        // Manage the blinking animation
        if (isOnline) {
            // Ensure the animator is created only once and reused
            if (!::blinkAnimator.isInitialized) {
                blinkAnimator = BlinkAnimator(icon, duration = 1500)
            }
            blinkAnimator.startBlinking()
            binding.layoutIpAddresses.root.visibility = View.VISIBLE

        } else {
            Log.d("OpenWRT","Setting status as Disconnected")

            /*Hiding the IP addresses layout*/
            binding.layoutIpAddresses.root.visibility = View.GONE

            // Stop the animation if it was started
            if (::blinkAnimator.isInitialized) {
                blinkAnimator.stopBlinking()
            }

            /*IPv4-IPv6*/
            binding.layoutIpAddresses.root.visibility = View.GONE

            /*DDNS*/
            binding.layoutDns.cardTitle.text = "OFFLINE"
            binding.layoutDns.cardDescription.text = "Your Pi-Starlink is unreachable, connect to Wi-Fi or through VPN."
        }
    }

    /* Call the OpenWRT in order to get system board information*/
    private fun callOpenWRTApi() {
        openWRTApi.executeCommand(
            systemBoardCommand,
            luciToken,
            onSuccess = { response ->
                /*Handle the successful response, update UI on the main thread*/
                activity?.runOnUiThread {
                    Log.d(openWRTTag,response.toString())

                    /*Update resource information*/
                    updateSystemBoardInformation(response)
                }
            },
            onFailure = { error ->
                /*Handle the failure, update UI on the main thread*/
                activity?.runOnUiThread {
                    Log.d(openWRTTag,error)
                }
            }
        )
    }

    /* Call the OpenWRT in order to get storage and cache board information*/
    private fun callOpenWRTStorageApi() {
        openWRTApi.executeCommand(
            systemBoardInformationCommand,
            luciToken,
            onSuccess = { response ->
                /*Handle the successful response, update UI on the main thread*/
                activity?.runOnUiThread {
                    Log.d(openWRTTag,response.toString())

                    /*Update resource information*/
                    updateSystemStorageInformation(response)
                }
            },
            onFailure = { error ->
                /*Handle the failure, update UI on the main thread*/
                activity?.runOnUiThread {
                    Log.d(openWRTTag,error)
                }
            }
        )
    }

    /* Call the OpenWRT in order to get the IPv6 address*/
    private fun callOpenWRTIPApi() {
        openWRTApi.executeCommand(
            getIPv6addressCommand,
            luciToken,
            onSuccess = { response ->
                /*Handle the successful response, update UI on the main thread*/
                activity?.runOnUiThread {
                    Log.d(openWRTTag,response.toString())

                    updateIPStatus(response)
                }
            },
            onFailure = { error ->
                /*Handle the failure, update UI on the main thread*/
                activity?.runOnUiThread {
                    Log.d(openWRTTag,error)
                }
            }
        )
    }

    /* Call OpenWRT in order to get */
    private fun callOpenWRTNetwork(onResult: (JSONObject?) -> Unit) {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            onResult(null)
            return
        }

        openWRTApi.executeCommand(
            getConnectedDevices,
            luciToken,
            onSuccess = { response ->
                /* Handle the successful response */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, response.toString())

                    /* Parsing the JSON Object */
                    try {
                        val resultString = response.optString("result")
                        val jsonObject = JSONObject(resultString)

                        /* Return the parsed JSON object */
                        onResult(jsonObject)

                        /* (Optional) You can still update the UI if needed */
                        updateNetworkUI(jsonObject)

                    } catch (e: Exception) {
                        e.printStackTrace()
                        onResult(null)
                    }
                }
            },
            onFailure = { error ->
                /* Handle the failure */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, error)
                    onResult(null)
                }
            }
        )
    }

    /* Update the network resources*/
    private fun updateNetworkUI(jsonObject: JSONObject) {
        /* Emptying the cards */
        dhcpLeases.clear()
        dhcp6Leases.clear()
        val parentLayout = view?.findViewById<LinearLayout>(R.id.network_card_container)
        val parentLayout6 = view?.findViewById<LinearLayout>(R.id.network_card6_container)
        parentLayout?.removeAllViews()
        parentLayout6?.removeAllViews()

        /* Parsing and updating the UI with the network devices */
        try {
            /* Append the network devices into the JSONArray for each IPv4 device */
            val leasesArray: JSONArray = jsonObject.getJSONArray("dhcp_leases")
            for (i in 0 until leasesArray.length()) {
                val leaseObject = leasesArray.getJSONObject(i)
                val lease = DhcpLease(
                    hostname = leaseObject.getString("hostname"),
                    ipaddr = leaseObject.getString("ipaddr"),
                    macAddr = leaseObject.getString("macaddr"),
                )
                dhcpLeases.add(lease)
            }

            /* Append the network devices into the JSONArray for each IPv6 device */
            val leases6Array: JSONArray = jsonObject.getJSONArray("dhcp6_leases")
            Log.d("OpenWRT Dhcp6", "$leases6Array")
            for (i in 0 until leases6Array.length()) {
                val lease6Object = leases6Array.getJSONObject(i)
                val lease6 = lease6Object?.getString("ip6addr")?.let {
                    DhcpLease(
                        hostname = lease6Object.optString("hostname").takeIf { it.isNotEmpty() }
                            ?: lease6Object.optString("macaddr").takeIf { it.isNotEmpty() }
                            ?: "Unknown",
                        ipaddr = it,
                        macAddr = lease6Object.optString("macaddr", ""),
                    )
                }
                if (lease6 != null) {
                    dhcp6Leases.add(lease6)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        /* Adding the network devices cards */
        addNetworkCards()
    }

    /* Call the OpenWRT in order to get port forwarding rules */
    private fun callOpenWRTPortForwarding() {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }

        openWRTApi.executeCommand(
            getRedirectFirewallRulesCommand,
            luciToken,
            onSuccess = { response ->
                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, response.toString())

                    /* Emptying the cards */
                    pfRules.clear()
                    val parentLayout = view?.findViewById<LinearLayout>(R.id.pf_card_container)
                    parentLayout?.removeAllViews()

                    /* Parsing the JSON Object */
                    try {
                        val resultString = response.optString("result")

                        // Split the response string by newline to handle multiple JSON objects
                        val jsonStrings = resultString.trim().split("\n")

                        if (jsonStrings.isNotEmpty()) {
                            /* Hide the rules status */
                            binding.fragmentPortForwardingIncluded.pfConfigStatus.visibility = View.GONE

                            // Iterate over each JSON string and parse it as a JSONObject
                            for (jsonStr in jsonStrings) {
                                val jsonObject = JSONObject(jsonStr)
                                /* Create a PortForwardingCard using the JSON data */
                                val lease = PortForwardingCard(
                                    id = jsonObject.optString("id"),
                                    title = jsonObject.optString("name"),
                                    externalPort = jsonObject.optString("src_dport"),
                                    destinationPort = jsonObject.optString("dest_port"),
                                    ipv6Target = jsonObject.optString("dest_ip"),
                                    enabled = jsonObject.optString("enabled")
                                )
                                pfRules.add(lease)
                            }
                        } else {
                            binding.fragmentPortForwardingIncluded.pfConfigStatus.visibility = View.VISIBLE
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    /* Get DHCv6 data*/
                    callOpenWRTNetwork { jsonObject ->
                        jsonObject?.let {
                            // Handle the JSON data
                            Log.d("OpenWRTNetwork", it.toString())

                            addPortForwardingCards(it)

                        } ?: run {
                            // Handle the error case
                            Log.e("OpenWRTNetwork", "Failed to retrieve data")
                        }
                    }
                    
                    /* Adding the network devices cards */
                }
            },
            onFailure = { error ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, error)
                }
            }
        )
    }

    /* Get VPN connected devices */
    private fun getOpenVPNConnectedDevices() {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }

        openWRTApi.executeCommand(
            getOpenVPNConnectedDevicesCommand,
            luciToken,
            onSuccess = { response ->
                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, response.toString())

                    /* Emptying the cards */
                    vpnDevices.clear()
                    val parentLayout = view?.findViewById<LinearLayout>(R.id.vpn_card_container)
                    parentLayout?.removeAllViews()

                    /* Parsing the JSON Object */
                    try {
                        val resultString = response.optString("result")
                        Log.d("OpenVPN", "resultString: $resultString")

                        val jsonObject = JSONObject(resultString)

                        val vpnArray: JSONArray = jsonObject.optJSONArray("ROUTING TABLE") ?: JSONArray()

                        if (vpnArray.length() == 0) {
                            Log.d("OpenVPN", "ROUTING TABLE array is empty")
                            binding.fragmentVpnIncluded.noConnectedDevice.visibility = View.VISIBLE
                        } else {
                            binding.fragmentVpnIncluded.noConnectedDevice.visibility = View.GONE
                            for (i in 0 until vpnArray.length()) {
                                val leaseObject = vpnArray.getJSONObject(i)

                                val commonName = leaseObject.optString("Common Name", "Unknown")
                                val virtualAddress = leaseObject.optString("Virtual Address", "Unknown")
                                val realAddress = leaseObject.optString("Real Address", "Unknown")

                                val lease = DhcpLease(
                                    hostname = commonName,
                                    ipaddr = virtualAddress,
                                    macAddr = realAddress,
                                )
                                vpnDevices.add(lease)
                            }
                        }

                    } catch (e: JSONException) {
                        e.printStackTrace()
                        Log.e(openWRTTag, "Error parsing JSON: ${e.message}")
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Log.e(openWRTTag, "Unexpected error: ${e.message}")
                    }

                    /* Adding the network devices cards */
                    addVpnCards()
                }
            },
            onFailure = { error ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, error)
                }
            }
        )
    }

    /* Setup OpenVPN */
    private fun setupVPN() {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }
        if (binding.fragmentVpnIncluded.vpnConfigActiveSlide.isLocked){
            Log.d("OpenVPN","Shouldn't active slider due to its status.")
            return
        }
        openWRTApi.executeCommand(
            setupVPNCommand,
            luciToken,
            onSuccess = { response ->
                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("OpenVPN", response.toString())
                    val resultString = response.optString("result").trim()
                    Log.d("OpenVPN","Config result is $resultString")
                    if (resultString == "OK"){
                        Log.d("OpenVPN","VPN configuration process has been started")
                        binding.fragmentVpnIncluded.vpnConfigStatus.text = "VPN configuration will take some time. Please come back here in a few minutes."
                        binding.fragmentVpnIncluded.setupTitle.text = "Configuration: IN PROGRESS"
                        binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
                        binding.fragmentVpnIncluded.vpnConfigActiveSlide.alpha =  0.5F
                        binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility =  View.GONE
                    }
                }
            },
            onFailure = { error ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = false,true)
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /* Check OpenVPN config status */
    private fun checkVPNConfigStatus() {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }

        openWRTApi.executeCommand(
            checkVPNConfigStatusCommand,
            luciToken,
            onSuccess = { response ->
                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("OpenVPN", response.toString())
                    val resultString = response.optString("result").trim()
                    if (resultString == "1"){
                        /* Handle in case of configuration in process */
                        Log.d("OpenVPN","VPN configuration is still in progress.")

                        /*Update resources*/
                        binding.fragmentVpnIncluded.vpnConfigActiveSlide.isLocked = true
                        binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.GONE
                        binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = true,true)
                    }
                    else{
                        if (resultString == "2"){
                            /* Handle in case of configuration done */
                            Log.d("OpenVPN","VPN configuration done!")
                            binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
                            binding.fragmentVpnIncluded.vpnConfigStatus.text = "VPN configuration is complete, use the slider below to connect."
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.isLocked = true
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.GONE
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = true,true)
                            binding.fragmentVpnIncluded.setupTitle.text = "Configuration: COMPLETED"

                            /*Trying to fetch the configuration file*/
                            fetchVPNConfiguration(client = "admin")
                        }
                        if (resultString == "0"){
                            /* Handle in case of configuration in idle */
                            Log.d("OpenVPN","VPN configuration is in idle.")
                            binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.GONE
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.alpha = 1F
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.VISIBLE
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = false,false)
                        }

                    }
                }
            },
            onFailure = { error ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Fetch vpn configuration*/
    private fun fetchVPNConfiguration(client: String) {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }

        openWRTApi.executeCommand(
            "$fetchVPNConfigurationFileCommand$client.ovpn",
            luciToken,
            onSuccess = { response ->
                activity?.runOnUiThread {
                    Log.d("OpenVPN", response.toString())
                    val resultString = response.optString("result").trim()

                    /*Check if resultString contains the file content*/
                    if (resultString.isNotEmpty()) {
                        /*Initialize FileHelper with context*/
                        val fileHelper = FileHelper(activity!!)
                        fileHelper.saveFileToInternalStorage(resultString, "$client.ovpn")
                        Log.d("OpenVPN","Config file has been successfully saved.")

                        /*Adding the admin profile to OpenVPN for Android*/
                        addNewOpenvpnProfile(client="admin")
                    } else {
                        Log.w("OpenVPN", "No content found in the response.")
                    }
                }
            },
            onFailure = { error ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Set vpn status to 0*/
    private fun setVPNConfiguration() {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }

        openWRTApi.executeCommand(
            setConfigAsFetched,
            luciToken,
            onSuccess = { response ->
                activity?.runOnUiThread {
                    Log.d("OpenVPN", response.toString())
                    val resultString = response.optString("result").trim()

                    Log.d("OpenVPN","Config status file has been successfully edited.")

                    binding.fragmentVpnIncluded.setupTitle.visibility = View.VISIBLE
                    binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
                    binding.fragmentVpnIncluded.setupTitle.text = "CONFIGURATION: COMPLETED"
                    binding.fragmentVpnIncluded.vpnConfigStatus.text = "VPN configuration is complete, use the slider below to connect."
                }
            },
            onFailure = { error ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Get DDNS configuration*/
    private fun getDDNSConfiguration() {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }
        openWRTApi.executeCommand(
            getDDNSConfig,
            luciToken,
            onSuccess = { response ->
                activity?.runOnUiThread {
                    Log.d("OpenVPN", response.toString())
                    val resultString = response.optString("result").trim()
                    Log.d("OpenVPN","DDNS configuration: $resultString")

                    /*Update resources*/
                    /* Assuming the resultString is in JSON format */
                    try {
                        val jsonResult = JSONObject(resultString)

                        /* Update resources */
                        /* Hostname */
                        val hostname = jsonResult.optString("lookup_host")
                        binding.fragmentDdnsIncluded.cardLayoutHostname.cardTitle.text = "Hostname"
                        binding.fragmentDdnsIncluded.cardLayoutHostname.cardDescription.setText(hostname)
                        hostnameInitialText = hostname

                        /* Username */
                        val username = jsonResult.optString("username")
                        binding.fragmentDdnsIncluded.cardLayoutUsername.cardTitle.text = "Username"
                        binding.fragmentDdnsIncluded.cardLayoutUsername.cardDescription.setText(username)
                        usernameInitialText = username

                        /* Password */
                        val password = jsonResult.optString("password")
                        binding.fragmentDdnsIncluded.cardLayoutPassword.cardTitle.text = "Password"
                        binding.fragmentDdnsIncluded.cardLayoutPassword.cardDescription.setText(password)
                        passwordInitialText = password

                    } catch (e: JSONException) {
                        Log.e("OpenVPN", "Failed to parse JSON: ${e.message}")
                    }
                }
            },
            onFailure = { error ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Set DDNS configuration*/
    private fun setDDNSConfiguration(command: String, value: String){
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }
        openWRTApi.executeCommand(
            "$command$value&&$commitDDNSCommand",
            luciToken,
            onSuccess = { response ->
                activity?.runOnUiThread {
                    Log.d("OpenVPN", response.toString())
                    val resultString = response.optString("result").trim()
                    Log.d("OpenVPN","DDNS configuration has been set: $resultString")

                    /*Update resources*/
                    /* Assuming the resultString is in JSON format */
                    try {
                        val jsonResult = JSONObject(resultString)

                    } catch (e: JSONException) {
                        Log.e("OpenVPN", "Failed to parse JSON: ${e.message}")
                    }
                }
            },
            onFailure = { error ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Set DDNS configuration*/
    private fun setFirewallConfiguration(command: String, id: String, value: String){
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }
        val composedCmd = String.format(command, id, value) + " && " + commitFirewallCommand
        Log.d("OpenWRT","Composed command: $composedCmd")
        openWRTApi.executeCommand(
            composedCmd ,
            luciToken,
            onSuccess = { response ->
                activity?.runOnUiThread {
                    Log.d("OpenVPN", response.toString())
                }
            },
            onFailure = { error ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Updating the system board information*/
    private fun updateSystemBoardInformation(data: JSONObject){
        val resultString = data.optString("result")
        val resultJsonObject = JSONObject(resultString)
        val releaseObject = resultJsonObject.optJSONObject("release")

        /*Board model Card*/
        binding.fragmentSystemIncluded.cardLayoutModel.cardTitle.text = "Model"
        binding.fragmentSystemIncluded.cardLayoutModel.cardDescription.text = resultJsonObject.optString("model").toString()

        /*Board kernel Card*/
        binding.fragmentSystemIncluded.cardLayoutKernel.cardTitle.text = "Kernel"
        binding.fragmentSystemIncluded.cardLayoutKernel.cardDescription.text = resultJsonObject.optString("kernel").toString()

        /*Board hostname Card*/
        binding.fragmentSystemIncluded.cardLayoutHostname.cardTitle.text = "Hostname"
        binding.fragmentSystemIncluded.cardLayoutHostname.cardDescription.text = resultJsonObject.optString("hostname").toString()

        /*Board system Card*/
        binding.fragmentSystemIncluded.cardLayoutSystem.cardTitle.text = "System"
        binding.fragmentSystemIncluded.cardLayoutSystem.cardDescription.text = resultJsonObject.optString("system").toString()

        /*Board revision Card*/
        binding.fragmentSystemIncluded.cardLayoutRevision.cardTitle.text = "Revision"
        binding.fragmentSystemIncluded.cardLayoutRevision.cardDescription.text = releaseObject.optString("revision")
    }

    /*Updating the system storage information*/
    private fun updateSystemStorageInformation(data: JSONObject){
        val resultString = data.optString("result")
        val resultJsonObject = JSONObject(resultString)

        /*Extract memory values*/
        val memoryObject = resultJsonObject.optJSONObject("memory")
        /*Extract root values*/
        val rootObject = resultJsonObject.optJSONObject("root")
        /*Extract tmp values*/
        val tmpObject = resultJsonObject.optJSONObject("tmp")

        val totalMemory = memoryObject?.optString("total")?.let { formatBytes(it.toLong()) }
        val freeMemory = memoryObject?.optString("free")?.let { formatBytes(it.toLong()) }
        val bufferedMemory = memoryObject?.optString("buffered")?.let { formatBytes(it.toLong()) }
        val cachedMemory = memoryObject?.optString("cached")?.let { formatBytes(it.toLong()) }
        val totUsed = formatBytes(
            (memoryObject?.optString("shared")?.toLong()!!) +
                    (memoryObject.optString("buffered").toLong()) +
                    (memoryObject.optString("cached").toLong()) +
                    (rootObject?.optString("used")?.toLong()!!) +
                    (tmpObject?.optString("used")?.toLong()!!)
        )


        /*Board Total Memory Available Card*/
        binding.fragmentSystemIncluded.cardLayoutTotalAvailable.cardTitle.text = "Available"
        binding.fragmentSystemIncluded.cardLayoutTotalAvailable.cardDescription.text = "$freeMemory/$totalMemory"
        updateProgressBar(
            memoryObject.optString("free").toLong(),
            memoryObject.optString("total").toLong(),
            binding.fragmentSystemIncluded.cardLayoutTotalAvailable.progressBar
        )

        /*Board Used Memory Card*/
        binding.fragmentSystemIncluded.cardLayoutUsed.cardTitle.text = "Used"
        binding.fragmentSystemIncluded.cardLayoutUsed.cardDescription.text = "$totUsed/$totalMemory"
        updateProgressBar(
            (memoryObject.optString("shared").toLong()) +
                    (memoryObject.optString("buffered").toLong()) +
                    (memoryObject.optString("cached").toLong()) +
                    (rootObject.optString("used").toLong()) +
                    (tmpObject.optString("used").toLong()),
            memoryObject.optString("total").toLong(),
            binding.fragmentSystemIncluded.cardLayoutUsed.progressBar
        )

        /*Board Buffered Memory Card*/
        binding.fragmentSystemIncluded.cardLayoutBuffered.cardTitle.text = "Buffered"
        binding.fragmentSystemIncluded.cardLayoutBuffered.cardDescription.text = "$bufferedMemory/$totalMemory"
        updateProgressBar(
            memoryObject.optString("buffered").toLong(),
            memoryObject.optString("total").toLong(),
            binding.fragmentSystemIncluded.cardLayoutBuffered.progressBar
        )

        /*Board Cached Memory Card*/
        binding.fragmentSystemIncluded.cardLayoutCached.cardTitle.text = "Cached"
        binding.fragmentSystemIncluded.cardLayoutCached.cardDescription.text = "$cachedMemory/$totalMemory"
        updateProgressBar(
            memoryObject.optString("cached").toLong(),
            memoryObject.optString("total").toLong(),
            binding.fragmentSystemIncluded.cardLayoutCached.progressBar
        )
    }

    /*Updating progress bar given two values and its element*/
    private fun updateProgressBar(bufferedMemory: Long, totalMemory: Long, progressBar: ProgressBar) {
        /*Calculate the percentage of buffered memory*/
        val progressPercentage = (bufferedMemory.toFloat() / totalMemory.toFloat()) * 100

        /*Applying the new progress value*/
        progressBar.progress = progressPercentage.toInt()
    }

    /*Updates the IP resources*/
    private fun updateIPStatus(data: JSONObject) {
        val resultString = data.optString("result")
        // Split the result by newline characters
        val addresses = resultString.split("\n")

        // Extract the IPv6 and IPv4 addresses
        val ipv6Address = addresses.firstOrNull { it.contains(":") }
        val ipv4Address = addresses.firstOrNull { it.matches(Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")) }

        /*IPv4-IPv6*/
        binding.layoutIpAddresses.cardTitle.text = "WAN"
        binding.layoutIpAddresses.cardDescription.text = "IPv4: $ipv4Address"
        binding.layoutIpAddresses.cardDescriptionTwo.text = "IPv6: $ipv6Address"

        /*DDNS*/
        binding.layoutDns.cardTitle.text = "DDNS"
        binding.layoutDns.cardDescription.text = "You haven't set up a DDNS yet."
    }

    /*Converting bytes into readable string*/
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        return String.format("%.2f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    /*Adding network cards at the view based on the array*/
    private fun addNetworkCards() {
        val parentLayout = view?.findViewById<LinearLayout>(R.id.network_card_container)
        val parentLayout6 = view?.findViewById<LinearLayout>(R.id.network_card6_container)

        for (lease in dhcpLeases) {
            val inflater = LayoutInflater.from(context)
            val cardView = inflater.inflate(R.layout.layout_card_network, parentLayout, false)

            cardView.findViewById<TextView>(R.id.card_title_hostname).text = lease.hostname
            cardView.findViewById<TextView>(R.id.card_description_ip_addr).text = lease.ipaddr
            cardView.findViewById<TextView>(R.id.card_description_mac_addr).text = lease.macAddr

            parentLayout?.addView(cardView)
        }

        for (lease in dhcp6Leases) {
            val inflater = LayoutInflater.from(context)
            val cardView = inflater.inflate(R.layout.layout_card_network6, parentLayout6, false)

            cardView.findViewById<TextView>(R.id.card_title_hostname).text = lease.hostname
            cardView.findViewById<TextView>(R.id.card_description_ip_addr).text = lease.ipaddr
            parentLayout6?.addView(cardView)
        }
    }

    /*Adding port forwarding cards at the view based on the array*/
    private fun addPortForwardingCards(dhcp6: JSONObject) {
        val parentLayout = view?.findViewById<LinearLayout>(R.id.pf_card_container)

        // Assuming `leases6Array` is already defined
        val leases6Array: JSONArray = dhcp6.getJSONArray("dhcp6_leases")
        val ipv6Addresses = mutableSetOf<String>()

        for (i in 0 until leases6Array.length()) {
            val leaseObject = leases6Array.getJSONObject(i)

            // Get the primary IPv6 address
            val primaryIp6 = leaseObject.optString("ip6addr")
            if (primaryIp6.isNotEmpty()) {
                ipv6Addresses.add(primaryIp6)
            }
            // Get the array of additional IPv6 addresses
            val ip6addrsArray = leaseObject.optJSONArray("ip6addrs")
            if (ip6addrsArray != null) {
                for (j in 0 until ip6addrsArray.length()) {
                    val ip6addr = ip6addrsArray.getString(j).split("/")[0] // Remove the subnet part
                    ipv6Addresses.add(ip6addr)
                }
            }
        }
        Log.d("OpenWRT", "IPv6: $ipv6Addresses")

        for (lease in pfRules) {
            val inflater = LayoutInflater.from(context)
            val cardView = inflater.inflate(R.layout.layout_card_port_forwarding, parentLayout, false)

            ipv6Addresses.add(lease.ipv6Target)
            val ipv6AddressList = ipv6Addresses.toList()

            cardView.findViewById<TextView>(R.id.title_text).text = lease.title
            cardView.findViewById<TextView>(R.id.external_port_description).text = lease.externalPort
            cardView.findViewById<TextView>(R.id.destination_port_description).text = lease.destinationPort

            val switchButton = cardView.findViewById<SwitchButton>(R.id.switch_button)
            switchButton.isChecked = lease.enabled == "1"
            switchButton.setOnCheckedChangeListener { _, isChecked ->
                // Handle the checked state change
                if (isChecked) {
                    setFirewallConfiguration(setPortForwardingEnabledCommand,lease.id,"1")
                } else {
                    setFirewallConfiguration(setPortForwardingEnabledCommand,lease.id,"0")
                }
            }

            // External Port Editing Logic
            var initialExtPortValue = lease.externalPort
            var isExternalPortEditing = false
            val externalPortEditImage = cardView.findViewById<ImageView>(R.id.edit_external_value_image)
            val externalPortCloseImage = cardView.findViewById<ImageView>(R.id.close_external_value_image)
            val externalPortEditText = cardView.findViewById<EditText>(R.id.external_port_description)

            externalPortEditImage.setOnClickListener {
                if (isExternalPortEditing) {
                    externalPortCloseImage.visibility = View.GONE

                    externalPortEditText.isFocusable = false
                    externalPortEditText.isFocusableInTouchMode = false
                    externalPortEditText.isClickable = false
                    externalPortEditText.clearFocus()

                    val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(externalPortEditText.windowToken, 0)

                    externalPortEditImage.setImageResource(R.drawable.pen)

                    initialExtPortValue = externalPortEditText.text.toString()

                    setFirewallConfiguration(setPortForwardingExternalPortCommand,lease.id,initialExtPortValue)
                } else {
                    externalPortCloseImage.visibility = View.VISIBLE

                    externalPortEditText.isFocusable = true
                    externalPortEditText.isFocusableInTouchMode = true
                    externalPortEditText.isClickable = true
                    externalPortEditText.requestFocus()

                    val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(externalPortEditText, InputMethodManager.SHOW_IMPLICIT)

                    externalPortEditImage.setImageResource(R.drawable.check)
                }
                isExternalPortEditing = !isExternalPortEditing
            }

            externalPortCloseImage.setOnClickListener {
                externalPortEditText.setText(initialExtPortValue)
                externalPortCloseImage.visibility = View.GONE

                externalPortEditText.isFocusable = false
                externalPortEditText.isFocusableInTouchMode = false
                externalPortEditText.isClickable = false
                externalPortEditText.clearFocus()

                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(externalPortEditText.windowToken, 0)

                externalPortEditImage.setImageResource(R.drawable.pen)

                isExternalPortEditing = false
            }

            // Destination Port Editing Logic
            var initialDestPortValue = lease.destinationPort
            var isDestinationPortEditing = false
            val destinationPortEditImage = cardView.findViewById<ImageView>(R.id.edit_destination_value_image)
            val destinationPortCloseImage = cardView.findViewById<ImageView>(R.id.close_destination_value_image)
            val destinationPortEditText = cardView.findViewById<EditText>(R.id.destination_port_description)

            destinationPortEditImage.setOnClickListener {
                if (isDestinationPortEditing) {
                    destinationPortCloseImage.visibility = View.GONE

                    destinationPortEditText.isFocusable = false
                    destinationPortEditText.isFocusableInTouchMode = false
                    destinationPortEditText.isClickable = false
                    destinationPortEditText.clearFocus()

                    val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(destinationPortEditText.windowToken, 0)

                    destinationPortEditImage.setImageResource(R.drawable.pen)

                    initialDestPortValue = destinationPortEditText.text.toString()

                    setFirewallConfiguration(setPortForwardingDestinationPortCommand,lease.id,initialDestPortValue)
                } else {
                    destinationPortCloseImage.visibility = View.VISIBLE

                    destinationPortEditText.isFocusable = true
                    destinationPortEditText.isFocusableInTouchMode = true
                    destinationPortEditText.isClickable = true
                    destinationPortEditText.requestFocus()

                    val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(destinationPortEditText, InputMethodManager.SHOW_IMPLICIT)

                    destinationPortEditImage.setImageResource(R.drawable.check)
                }
                isDestinationPortEditing = !isDestinationPortEditing
            }

            destinationPortCloseImage.setOnClickListener {
                destinationPortEditText.setText(initialDestPortValue)
                destinationPortCloseImage.visibility = View.GONE

                destinationPortEditText.isFocusable = false
                destinationPortEditText.isFocusableInTouchMode = false
                destinationPortEditText.isClickable = false
                destinationPortEditText.clearFocus()

                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(destinationPortEditText.windowToken, 0)

                destinationPortEditImage.setImageResource(R.drawable.pen)

                isDestinationPortEditing = false
            }

            // Spinner setup for target IP addresses
            val targetIpSpinner = cardView.findViewById<Spinner>(R.id.target_description)
            val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, ipv6AddressList)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            targetIpSpinner.adapter = adapter

            // Set the selected IP address if it's available in lease.targetIp
            val selectedIpPosition = ipv6AddressList.indexOf(lease.ipv6Target)
            if (selectedIpPosition >= 0) {
                targetIpSpinner.setSelection(selectedIpPosition)
            }

            var isInitialSelection = true
            targetIpSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    // Check if this is the initial setup call
                    if (isInitialSelection) {
                        isInitialSelection = false
                        return
                    }

                    // Get the selected item
                    val selectedItem = parent.getItemAtPosition(position).toString()
                    // Handle the selected item
                    println("Selected item: $selectedItem")

                    setFirewallConfiguration(setPortForwardingTargetIPCommand,lease.id,selectedItem)
                }

                override fun onNothingSelected(parent: AdapterView<*>) {
                    // Handle the case when no item is selected (optional)
                }
            }


            parentLayout?.addView(cardView)
        }
    }

    /*Adding network cards at the view based on the array*/
    private fun addVpnCards() {
        val parentLayout = view?.findViewById<LinearLayout>(R.id.vpn_card_container)

        for (lease in vpnDevices) {
            val inflater = LayoutInflater.from(context)
            val cardView = inflater.inflate(R.layout.layout_card_network, parentLayout, false)

            cardView.findViewById<TextView>(R.id.card_title_hostname).text = lease.hostname
            cardView.findViewById<TextView>(R.id.card_description_ip_addr).text = lease.ipaddr
            cardView.findViewById<TextView>(R.id.card_description_mac_addr).text = lease.macAddr

            parentLayout?.addView(cardView)
        }

    }

    /*Updates the VPN Layout base on profile existence*/
    private fun updateVPNLayout() {
        val vpnList = listVPNs()

        // Check if the list is empty
        if (vpnList.isEmpty()) {
            Log.d("OpenVPN", "No VPN profiles found.")

            if (!isBoardReachable){
                binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
                binding.fragmentVpnIncluded.vpnConfigStatus.text = "Pi-Starlink is unreachable, please connect to its Wi-Fi."
                binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.GONE
            }
            else{
                binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.VISIBLE
                binding.fragmentVpnIncluded.vpnConfigActiveSlide.isLocked = false
            }

            /*Update the resource status*/
            binding.fragmentVpnIncluded.vpnActiveSlide.alpha = 0.5F
            binding.fragmentVpnIncluded.vpnActiveSlide.isLocked = true
            binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = false,false)

            /*If no profiles have been found, checking the configuration status*/
            checkVPNConfigStatus()

        } else {
            // Check if the list contains an element with "Pi-Starlink"
            val matchingProfile = vpnList.find { it.contains("Pi-Starlink") }

            if (matchingProfile != null) {
                Log.d("OpenVPN", "VPN profiles contain 'Pi-Starlink': $vpnList")

                // Extract the UUID from the matching profile (assuming the format is "Name:UUID")
                profileUUID = matchingProfile.split(":").getOrNull(1)?.trim()

                if (profileUUID != null) {
                    // Use profileUUID as needed
                    Log.d("OpenVPN", "Profile UUID: $profileUUID")
                    binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = true, false)
                    binding.fragmentVpnIncluded.vpnActiveSlide.isLocked = false

                    binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.GONE

                    binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
                    binding.fragmentVpnIncluded.vpnConfigStatus.text = "VPN configuration is complete, use the slider below to connect."
                    binding.fragmentVpnIncluded.setupTitle.text = "Configuration: COMPLETED"
                } else {
                    Log.d("OpenVPN", "No UUID found in the matching profile.")
                }
            } else {
                Log.d("OpenVPN", "VPN profiles do not contain 'Pi-Starlink': $vpnList")
            }
        }
    }

    /*Adding a new VPN profile*/
    private fun addNewOpenvpnProfile(client: String) {
        try {
            /*Path to the configuration file in internal storage*/
            val fileName = "$client.ovpn"
            val fileDir = activity?.filesDir
            val file = File(fileDir, fileName)

            if (!file.exists()) {
                Log.e("OpenVPN", "Configuration file not found: ${file.absolutePath}")
                return
            }

            /*Read the configuration file*/
            val fis = FileInputStream(file)
            val br = BufferedReader(InputStreamReader(fis))
            val config = StringBuilder()
            var line: String?
            while (true) {
                line = br.readLine()
                if (line == null) break
                config.append(line).append("\n")
            }
            br.close()
            fis.close()

            Log.d("OpenVPN","Load configuration file: $config")

            /*Add the new VPN profile*/
            val name = profileName
            val profile = mService?.addNewVPNProfile(name, false, config.toString())
            Log.d("OpenVPN", "Profile added: $profile")

            /*Update the VPN layout resources*/
            binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.GONE
            binding.fragmentVpnIncluded.vpnConfigStatus.alpha = 1F

            binding.fragmentVpnIncluded.vpnConfigActiveSlide.alpha = 1F
            binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = true,false)

            binding.fragmentVpnIncluded.vpnActiveSlide.alpha = 1F
            binding.fragmentVpnIncluded.vpnActiveSlide.isLocked = false

        } catch (e: IOException) {
            Log.e("OpenVPN", "Error reading file: ${e.message}")
        } catch (e: RemoteException) {
            Log.e("OpenVPN", "RemoteException: ${e.message}")
        } catch (e: Exception) {
            // Catch any other exceptions to prevent the app from crashing
            Log.e("OpenVPN", "Exception: ${e.message}")
        }
        Log.d("OpenVPN", "Profile has been started/added")

        setVPNConfiguration()
    }

    /**
     * Class for interacting with the main interface of the service.
     */
    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.d("OpenVPN","Connected to the service...")
            // Cast the IBinder object into the IOpenVPNAPIService interface
            mService = IOpenVPNAPIService.Stub.asInterface(service)

            val activity = this@HomeFragment.activity
            val serviceInstance = mService

            if (activity != null && serviceInstance != null) {
                try {
                    // Request permission to use the API
                    val intent = serviceInstance.prepare(activity.packageName)
                    if (intent != null) {
                        startActivityForResult(intent, icsOpenvpnPermission)
                    } else {
                        onActivityResult(icsOpenvpnPermission, Activity.RESULT_OK, null)
                    }
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            } else {
                Log.e("OpenVPN", "Service or Activity is null")
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            // Set mService to null when the service is disconnected
            mService = null
            Log.d("OpenVPN","onServiceDisconnected")
        }
    }

    /*Binds OpenVPN Service*/
    private fun bindService() {
        val icsOpenVpnService = Intent(IOpenVPNAPIService::class.java.name)
        icsOpenVpnService.setPackage("de.blinkt.openvpn")
        activity!!.bindService(icsOpenVpnService, mConnection, Context.BIND_AUTO_CREATE)
        Log.d("OpenVPN","Service has been bound: $mConnection, $mService")
    }

    /*Unbinds OpenVPN Service*/
    private fun unbindService() {
        activity!!.unbindService(mConnection)
    }

    /*Prepares OpenVPN starting profile*/
    @Throws(RemoteException::class)
    private fun prepareStartProfile(requestCode: Int) {
        val requestPermission = mService!!.prepareVPNService()
        if (requestPermission == null) {
            onActivityResult(requestCode, Activity.RESULT_OK, null)
        } else {
            // Have to call an external Activity since services cannot used onActivityResult
            startActivityForResult(requestPermission, requestCode)
        }
    }

    /*Returns the list of the available VPNs*/
    private fun listVPNs(): List<String> {
        val resultList = mutableListOf<String>()
        try {
            val list = mService?.profiles
            if (list != null) {
                for (vp in list.subList(0, 5.coerceAtMost(list.size))) {
                    val profileInfo = "${vp.mName}:${vp.mUUID}"
                    resultList.add(profileInfo)
                }
                Log.d("OpenVPN", "Profiles: $resultList")
            } else {
                Log.d("OpenVPN", "Service is not available.")
            }
        } catch (e: RemoteException) {
            Log.d("OpenVPN", "Error: ${e.message}")
        }
        return resultList
    }

    /*Connects the VPN based on the profile UUID*/
    private fun connectVPN(profileUUID: String) {
        Log.d("OpenVPN", "Attempting to start the OpenVPN profile.")

        // Attempt to start the profile and listen for status updates
        try {
            mService?.startProfile(profileUUID)

            // Register callback for receiving status updates
            mService?.registerStatusCallback(object : IOpenVPNStatusCallback.Stub() {
                override fun newStatus(uuid: String, state: String, message: String, level: String) {
                    //Log.d("OpenVPN", "New Status - UUID: $uuid, State: $state, Message: $message, Level: $level")

                    /*Update UI or handle state changes based on the message*/
                    activity?.runOnUiThread {
                        Log.d("OpenVPN","State: $message")
                        if ("SUCCESS" in message) {
                            // Extracting the IPv4 address from the message
                            val parts = message.split(",")
                            myOpenVPNIPv4Addr = if (parts.size > 1) {
                                parts[1]
                            } else {
                                ""
                            }
                            Log.d("OpenVPN","VPN has been successfully activated.")

                            isVpnConnected = true

                            /*Update the activation slider resource*/
                            updateActivationSliderStatus(true)
                        }
                    }
                }
            })
        } catch (e: RemoteException) {
            e.printStackTrace()
            Log.e("OpenVPN", "Failed to start the VPN profile: ${e.message}")
            updateActivationSliderStatus(false)
            isVpnConnected = false
        }
    }

    /*Connects the VPN based on the profile UUID*/
    private fun disconnectVPN() {
        Log.d("OpenVPN", "Attempting to disconnect the OpenVPN profile.")

        // Attempt to start the profile and listen for status updates
        try {
            mService?.disconnect()

            // Register callback for receiving status updates
            mService?.registerStatusCallback(object : IOpenVPNStatusCallback.Stub() {
                override fun newStatus(uuid: String, state: String, message: String, level: String) {
                    //Log.d("OpenVPN", "New Status - UUID: $uuid, State: $state, Message: $message, Level: $level")

                    /*Update UI or handle state changes based on the message*/
                    activity?.runOnUiThread {
                        Log.d("OpenVPN","State: $message")
                        if ("No process running" in message) {
                            Log.d("OpenVPN","VPN has been successfully deactivated.")

                            isVpnConnected = false

                            /*Update the activation slider resource*/
                            updateActivationSliderStatus(false)
                        }
                    }
                }
            })
        } catch (e: RemoteException) {
            e.printStackTrace()
            Log.e("OpenVPN", "Failed to deactivate the VPN profile: ${e.message}")
            updateActivationSliderStatus(true)
            isVpnConnected = true
        }
    }

    /*Update the activating slider status*/
    private fun updateActivationSliderStatus(activated: Boolean){
        if (activated) {
            binding.fragmentVpnIncluded.vpnActiveSlide.isReversed = true
            binding.fragmentVpnIncluded.vpnActiveSlide.outerColor =
                Color.parseColor("#FF0000") /*Red*/
            binding.fragmentVpnIncluded.vpnActiveSlide.setCompleted(completed = false, true)
            binding.fragmentVpnIncluded.vpnActiveSlide.text = "Slide to disconnect"
            binding.fragmentVpnIncluded.vpnStatusTitle.text = "CONNECTED as $myOpenVPNIPv4Addr"
        }
        else{
            binding.fragmentVpnIncluded.vpnActiveSlide.isReversed = false
            binding.fragmentVpnIncluded.vpnActiveSlide.outerColor =
                Color.parseColor("#1F1F1E") /*White*/
            binding.fragmentVpnIncluded.vpnActiveSlide.setCompleted(completed = false, true)
            binding.fragmentVpnIncluded.vpnActiveSlide.text = "Slide to active"
            binding.fragmentVpnIncluded.vpnStatusTitle.text = "DISCONNECTED"
        }
    }

    /*On activity result coming from OpenVPN For Android*/
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d("OpenVPN Codes","requestCode: $requestCode, resultCode: $resultCode, data: $data")
    };
}

/**
 * Blinks the ImageView by animating its opacity.
 */
class BlinkAnimator(private val imageView: ImageView, private val duration: Long = 1000) {

    private var animator: ObjectAnimator? = null
    private var isBlinking = false

    /**
     * Starts the blinking animation.
     */
    fun startBlinking() {
        if (isBlinking) return // If already blinking, do nothing

        animator = ObjectAnimator.ofFloat(imageView, "alpha", 1f, 0f, 1f).apply {
            this.duration = this@BlinkAnimator.duration
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
        }
        animator?.start()
        isBlinking = true
    }

    /**
     * Stops the blinking animation.
     */
    fun stopBlinking() {
        animator?.cancel()
        isBlinking = false
    }
}

/** Class to handle local files **/
class FileHelper(private val context: Context) {

    fun saveFileToInternalStorage(fileContent: String, fileName: String) {
        try {
            // Get the directory for the app's private files
            val fileDir = context.filesDir
            val file = File(fileDir, fileName)

            FileOutputStream(file).use { output ->
                output.write(fileContent.toByteArray())
                output.flush()
            }

            Log.d("OpenVPN", "File saved to ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e("OpenVPN", "Error saving file: ${e.message}")
        }
    }
}

/** Javascript interface
 *
 */
class JavaScriptInterface(
    private val context: Context,
    private val homeFragment: HomeFragment
) {

    @JavascriptInterface
    fun getConnectionStatus(): Boolean {
        Log.d("Webview", "getConnectionStatus has been called from Javascript")
        val someVariable = homeFragment.isBoardReachable
        return someVariable
    }
}






