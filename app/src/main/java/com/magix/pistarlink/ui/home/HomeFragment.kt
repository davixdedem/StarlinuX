package com.magix.pistarlink.ui.home

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.IBinder
import android.os.RemoteException
import android.text.Html
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.addCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.magix.pistarlink.BuildConfig
import com.magix.pistarlink.DbHandler
import com.magix.pistarlink.MyVpnService
import com.magix.pistarlink.OpenWRTApi
import com.magix.pistarlink.R
import com.magix.pistarlink.databinding.FragmentHomeBinding
import com.ncorti.slidetoact.SlideToActView
import com.suke.widget.SwitchButton
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.pow

class HomeFragment : Fragment(), MyVpnServiceCallback {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var webView: WebView

    /*Init*/
    private lateinit var openWRTApi: OpenWRTApi
    private lateinit var luciToken: String
    private lateinit var job: Job
    private var canCallHomeAPi = false
    var isBoardReachable = false
    private lateinit var dbHandler: DbHandler
    private lateinit var luciUsername : String
    private lateinit var luciPassword : String
    private lateinit var clipboard: ClipboardManager

    /*Vpn*/
    private var mService: IOpenVPNAPIService? = null
    private var profileUUID: String? = null
    private var isVpnConnected = false
    private var myOpenVPNIPv4Addr = ""
    private val profileName = "Pi-Starlink"
    private val icsOpenvpnPermission: Int = 7
    private val vpnDevices = mutableListOf<DhcpLease>()
    private val wgVpnDevices = mutableListOf<DhcpLease>()
    private val maxExpirationTimer = 3600

    /*Notifcations*/
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

    /*Wireguard*/
    private lateinit var backend: Backend
    private lateinit var tunnel: Tunnel
    private var isWireguardConnected = false
    private var wireguardVPNIPv4Addr = ""

    /*Port Forwarding*/
    private lateinit var lastIPv6Data: JSONObject

    /*DDNS*/
    private var isHostnameEditing : Boolean = false
    private var isUsernameEditing : Boolean = false
    private var isPasswordEditing : Boolean = false
    private var isWirelessPasswordEditing : Boolean = false
    private var isSSIDEditing: Boolean = false
    private var isLuciUsernameEditing: Boolean = false
    private var isLuciPasswordEditing: Boolean = false
    private var hostnameInitialText : String = ""
    private var usernameInitialText : String = ""
    private var passwordInitialText : String = ""
    private var ssidInitialText: String = ""
    private var wirelessPasswordInitialText: String = ""
    private var luciUsernameInitialText: String = ""
    private var luciPasswordInitialText: String = ""

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

    /*DHCP*/
    private val dhcpLeases = mutableListOf<DhcpLease>()
    private val dhcp6Leases = mutableListOf<DhcpLease>()
    private val pfRules = mutableListOf<PortForwardingCard>()

    /*Luci Configurations*/
    private val baseUrl = "http://192.168.1.1"

    /*Luci Commands*/
    private val systemBoardCommand = "ubus call system board"
    private val systemBoardInformationCommand = "ubus call system info"
    private val getIPv6addressCommand = "ip -6 addr show dev eth0 | grep inet6 | awk '{ print \$2 }' | awk -F'/' '{ print \$1 }' | grep '^2a0d' | head -n 1; ip -4 addr show dev eth0 | grep inet | awk '{ print \$2 }' | awk -F'/' '{ print \$1 }' | head -n 1"
    private val getConnectedDevices = "ubus call luci-rpc getDHCPLeases"
    private val getOpenVPNConnectedDevicesCommand = "bash /root/scripts/openvpn_devices.sh"
    private val getWireguardConnectedDevicesCommand = "bash /root/scripts/wireguard_devices.sh"
    private val setupVPNCommand = "bash /root/scripts/openvpn_configure_callback.sh"
    private val checkVPNConfigStatusCommand = "cat /root/scripts/vpn_config_status"
    private val setupWireguardCommand = "bash /root/scripts/wireguard_configure_callback.sh"
    private val checkWireguardConfigStatusCommand = "cat /root/scripts/wireguard_config_status"
    private val fetchWireguardConfigurationFileCommand = "cat /etc/config/wireguard/"
    private val fetchVPNConfigurationFileCommand = "cat /etc/openvpn/"
    private val setConfigAsFetched = "echo '0' > /root/scripts/vpn_config_status "
    private val setWireguardConfigAsFetched = "echo '0' > /root/scripts/wireguard_config_status "
    private val getDDNSConfig = "echo \\\"{\\\\\\\"lookup_host\\\\\\\":\\\\\\\"\$(uci get ddns.myddns_ipv6.lookup_host)\\\\\\\", \\\\\\\"username\\\\\\\":\\\\\\\"\$(uci get ddns.myddns_ipv6.username)\\\\\\\", \\\\\\\"password\\\\\\\":\\\\\\\"\$(uci get ddns.myddns_ipv6.password)\\\\\\\"}\\\""
    private val setDDNSHostname = "uci set ddns.myddns_ipv6.lookup_host="
    private val setDDNSDomain = "uci set ddns.myddns_ipv6.domain="
    private val setDDNSUsername = "uci set ddns.myddns_ipv6.username="
    private val setDDNSPassword = "uci set ddns.myddns_ipv6.password="
    private val commitDDNSCommand  = "uci commit ddns"
    private val getRedirectFirewallRulesCommand  = "bash /root/scripts/port_forwarding_configurations.sh"
    private val setPortForwardingExternalPortCommand = "uci set firewall.@redirect['%s'].src_dport='%s'"
    private val setPortForwardingDestinationPortCommand = "uci set firewall.@redirect['%s'].dest_port='%s'"
    private val setPortForwardingTargetIPCommand = "uci set firewall.@redirect['%s'].dest_ip='%s'"
    private val setPortForwardingEnabledCommand = "uci set firewall.@redirect['%s'].enabled='%s'"
    private val deletePortForwardingRuleCommand = "uci delete firewall.@redirect['%s']"
    private val addNewFirewallRuleCommand = """
        uci add firewall redirect
        uci set firewall.@redirect[-1].dest='lan'
        uci set firewall.@redirect[-1].target='DNAT'
        uci set firewall.@redirect[-1].name='%s'
        uci set firewall.@redirect[-1].src='wan'
        uci set firewall.@redirect[-1].src_dport='5555'
        uci set firewall.@redirect[-1].dest_ip='fdb7:3e6b:bfff::2a4'
        uci set firewall.@redirect[-1].dest_port='5555'
        uci set firewall.@redirect[-1].family='ipv6'
        uci set firewall.@redirect[-1].enabled='0'
    """.trimIndent()
    private val commitFirewallCommand  = "uci commit firewall"
    private val commitWirelessCommand  = "uci commit wireless"
    private val restartFirewallCommand  = "/etc/init.d/firewall reload"
    private val getWirelessConfigCommand = "echo \\\"{\\\\\\\"ssid\\\\\\\":\\\\\\\"\$(uci get wireless.@wifi-iface[0].ssid)\\\\\\\", \\\\\\\"key\\\\\\\":\\\\\\\"\$(uci get wireless.@wifi-iface[0].key)\\\\\\\"}\\\""
    private val setWirelessSSIDCommand = "uci set wireless.@wifi-iface[0].ssid='%s'"
    private val setWirelessPasswordCommand = "uci set wireless.@wifi-iface[0].key='%s'"
    private val restartRouterCommand = "reboot"
    private val factoryResetCommand = "bash /root/scripts/factory_reset.sh"

    /*Configurations*/
    private val onlineStatus = "Online"
    private val offlineStatus = "Disconnected"
    private val openWRTTag = "OpenWRT"
    private lateinit var blinkAnimator: BlinkAnimator

    override fun onStart() {
        super.onStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /*Callback for back button*/
        requireActivity().onBackPressedDispatcher.addCallback(this) {
            closeFragment()
        }

    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Initialize the DbHandler
        dbHandler = DbHandler(requireContext())

        /*Prepare DB configurations*/
        prepareDBConfig()

        /*Init the OpenWRTApi class*/
        luciUsername = dbHandler.getConfiguration("luci_username").toString()
        luciPassword = dbHandler.getConfiguration("luci_password").toString()
        Log.d("OpenWRT","$luciUsername,$luciPassword")
        openWRTApi = OpenWRTApi(baseUrl, luciUsername, luciPassword)

        /*Init clipboard*/
        clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        /*Try to login to Luci on time*/
        performLoginAndUpdate(true)

        /*Check if OpenVPN For Android is installed*/
        val isInstalled = isAppInstalled("de.blinkt.openvpn")
        if(!isInstalled){
            binding.layoutVpn.root.visibility = View.VISIBLE
            binding.layoutVpn.cardTitle.text = "VPN"
            val text = "<a href=\"https://play.google.com/store/apps/details?id=de.blinkt.openvpn\">OpenVPN For Android</a> is not installed."
            binding.layoutVpn.cardDescription.text = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
            binding.layoutVpn.root.setOnClickListener(){
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=de.blinkt.openvpn"))
                startActivity(webIntent)
            }
        }
        else{
            binding.layoutVpn.root.visibility = View.GONE
        }

        /*Binding OpenVPN Service*/
        bindService()

        // If you want to programmatically trigger the back button action:
        root.setOnClickListener {
            val callback = requireActivity().onBackPressedDispatcher.addCallback(this) {
                closeFragment()
            }
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        }

        return root
    }

    @OptIn(DelicateCoroutinesApi::class)
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
                GlobalScope.launch(Dispatchers.IO) {
                    /*Call the OpenWRT Api in order to popule the system board information*/
                    callOpenWRTApi()
                }

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

        /*Binding clipboard DDNS buttons*/
        binding.layoutDns.copyIpv4Image.setOnClickListener {
            val textToCopy = binding.layoutDns.cardDescription.text
            val clip = ClipData.newPlainText("Copied Text", textToCopy)
            clipboard.setPrimaryClip(clip)
        }

        /*Binding clipboard IPv4 buttons*/
        binding.layoutIpAddresses.copyIpv4Image.setOnClickListener {
            val textToCopy = binding.layoutIpAddresses.cardDescription.text
            val clip = ClipData.newPlainText("Copied Text", textToCopy)
            clipboard.setPrimaryClip(clip)
        }

        /*Binding clipboard IPv6 buttons*/
        binding.layoutIpAddresses.copyIpv6Image.setOnClickListener {
            val textToCopy = binding.layoutIpAddresses.cardDescriptionTwo.text
            val clip = ClipData.newPlainText("Copied Text", textToCopy)
            clipboard.setPrimaryClip(clip)
        }

        /*Updating the text for versioning*/
        val version = BuildConfig.VERSION_NAME
        binding.textView3.text = "$version"

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

        /*** START - OPENVPN FRAGMENT ***/
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
            /*Check last VPN used*/
            val lastVPNUsed = dbHandler.getConfiguration("lastVPNUsed")
            Log.d("VPN-Handler","Last VPN is: $lastVPNUsed")

            /*lastVPNUsed is OpenVPN*/
            if (lastVPNUsed == "OpenVPN") {
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentVpnIncluded.root.visibility = View.VISIBLE

                updateVPNLayout()

                if (isBoardReachable) {
                    getOpenVPNConnectedDevices()
                }
            }

            /*lastVPNUsed is Wireguard*/
            else  {
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentVpnIncludedWg.root.visibility = View.VISIBLE

                updateWireguardVPNLayout()

                if (isBoardReachable) {
                    getWireguardConnectedDevices()
                }
            }

            binding.fragmentVpnIncluded.vpnActiveStatus.visibility = View.GONE

            /*Pause main call API*/
            canCallHomeAPi = false

            /*Get the current VPN chosen*/

        }
        binding.vpnSectionBtn.setOnClickListener {
            /*Handle its view visibility*/
            binding.homeMainLayout.visibility = View.GONE
            binding.fragmentVpnIncluded.root.visibility = View.VISIBLE

            binding.fragmentVpnIncluded.vpnActiveStatus.visibility = View.GONE

            /*Pause main call API*/
            canCallHomeAPi = false

            updateVPNLayout()

            if (isBoardReachable) {
                getOpenVPNConnectedDevices()
            }
        }

        /*Binding OpenVPN switcher*/
        binding.fragmentVpnIncluded.titleSbcInfo.setOnClickListener {
            if (!isWireguardConnected) {
                binding.fragmentVpnIncludedWg.root.visibility = View.GONE
                binding.fragmentVpnIncluded.root.visibility = View.VISIBLE
            }
        }
        binding.fragmentVpnIncluded.titleSbcInfoWg.setOnClickListener {
            if (!isVpnConnected) {
                binding.fragmentVpnIncluded.root.visibility = View.GONE
                binding.fragmentVpnIncludedWg.root.visibility = View.VISIBLE
            }
            /*Update wireguard layout*/
            updateWireguardVPNLayout()

            if (isBoardReachable) {
                getWireguardConnectedDevices()
            }
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
                    Log.d("VPN-Configuration", "Configuration slide completed.")
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
        binding.fragmentVpnIncluded.vpnActiveSlide.onSlideCompleteListener = object : SlideToActView.OnSlideCompleteListener {
            override fun onSlideComplete(view: SlideToActView) {
                // Hide the VPN active status view
                binding.fragmentVpnIncluded.vpnActiveStatus.visibility = View.GONE
                Log.d("OpenVPN", "Slide activation completed: $profileUUID")

                binding.fragmentVpnIncluded.vpnStatusTitle.text = "CHECKING NETWORK..."

                // Launch a coroutine for network checking
                GlobalScope.launch(Dispatchers.IO) {
                    // Check if the VPN is not connected
                    if (!isVpnConnected) {
                        // Perform network status check in a background thread
                        val networkStatus = context?.let { checkNetworkStatus(it) }
                        Log.d("OpenVPN", "Network code is: $networkStatus")

                        // Switch to Main thread to update the UI or call VPN activation
                        withContext(Dispatchers.Main) {
                            if (networkStatus == 0) {
                                handleVPNActivation()  // UI update or VPN activation on the main thread
                            } else {
                                handleNetworkError(networkStatus)  // Handle error on the main thread
                            }
                        }
                    } else {
                        // Disconnect VPN (can be done on the main thread as well)
                        withContext(Dispatchers.Main) {
                            disconnectVPN()
                        }
                    }
                }
            }

            private fun handleNetworkError(networkStatus: Int?) {
                when (networkStatus) {
                    1 -> binding.fragmentVpnIncluded.vpnActiveStatus.text =
                        "An error occurred: \n No Wi-Fi or mobile data enabled."
                    2 -> binding.fragmentVpnIncluded.vpnActiveStatus.text =
                        "An error occurred: \n Wi-Fi or mobile data enabled, but no Internet access."
                    3 -> binding.fragmentVpnIncluded.vpnActiveStatus.text =
                        "An error occurred: \n Unable to reach the endpoint due to the absence of IPv6 support from your current ISP.\n"
                }

                binding.fragmentVpnIncluded.vpnStatusTitle.text = "DISCONNECTED"
                binding.fragmentVpnIncluded.vpnActiveStatus.visibility = View.VISIBLE
                binding.fragmentVpnIncluded.vpnActiveSlide.setCompleted(completed=false, true)
            }
        }
        /*** END - OPENVPN FRAGMENT ***/

        /*** START - WIREGUARD FRAGMENT ***/
        /*Applying effect at Vpn buttons*/

        /*Binding Wireguard switcher*/
        binding.fragmentVpnIncludedWg.titleSbcInfo.setOnClickListener {
            if (!isWireguardConnected) {
                updateVPNLayout()
                binding.fragmentVpnIncludedWg.root.visibility = View.GONE
                binding.fragmentVpnIncluded.root.visibility = View.VISIBLE
            }
        }
        binding.fragmentVpnIncludedWg.titleSbcInfoWg.setOnClickListener {
            binding.fragmentVpnIncluded.root.visibility = View.GONE
            binding.fragmentVpnIncludedWg.root.visibility = View.VISIBLE
        }

        /*Binding Vpn exit info button*/
        binding.fragmentVpnIncludedWg.exitImage.setOnClickListener {
            binding.homeMainLayout.visibility = View.VISIBLE
            binding.fragmentVpnIncludedWg.root.visibility = View.GONE

            /*Pause main call API*/
            canCallHomeAPi = true

            /*Populating home resources*/
            performLoginAndUpdate()
        }

        /*Applying effect at Vpn exit button*/
        binding.fragmentVpnIncludedWg.exitImage.setOnTouchListener { view, motionEvent ->
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
        binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.onSlideCompleteListener =
            object : SlideToActView.OnSlideCompleteListener {
                override fun onSlideComplete(view: SlideToActView) {
                    Log.d("Wireguard-Configuration", "Configuration slide completed.")
                    if (isBoardReachable) {
                        setupWireguardVPN()
                    } else {
                        binding.fragmentVpnIncludedWg.vpnConfigStatus.text =
                            "Pi-Starlink is unreachable. Please connect to its Wi-Fi."
                        binding.fragmentVpnIncludedWg.vpnConfigStatus.visibility = View.VISIBLE
                    }
                }
            }

        /*Binding activation slide button*/
        binding.fragmentVpnIncludedWg.vpnActiveSlide.onSlideCompleteListener = object : SlideToActView.OnSlideCompleteListener {
            override fun onSlideComplete(view: SlideToActView) {
                // Hide the VPN active status view
                binding.fragmentVpnIncludedWg.vpnActiveStatus.visibility = View.GONE
                binding.fragmentVpnIncludedWg.vpnStatusTitle.text = "CHECKING NETWORK..."

                // Launch a coroutine for network checking
                GlobalScope.launch(Dispatchers.IO) {
                    // Check if the VPN is not connected
                    if (!isWireguardConnected) {
                        // Perform network status check in a background thread
                        val networkStatus = context?.let { checkNetworkStatus(it) }
                        Log.d("Wireguard", "Network code is: $networkStatus")

                        // Switch to Main thread to update the UI or call VPN activation
                        withContext(Dispatchers.Main) {
                            if (networkStatus == 0) {
                                handleWireguardVPNActivation()  // UI update or VPN activation on the main thread
                            } else {
                                handleNetworkError(networkStatus)  // Handle error on the main thread
                            }
                        }
                    } else {
                        // Disconnect VPN (can be done on the main thread as well)
                        withContext(Dispatchers.Main) {
                            disconnectWireguardVPN()
                        }
                    }
                }
            }

            private fun handleNetworkError(networkStatus: Int?) {
                when (networkStatus) {
                    1 -> binding.fragmentVpnIncludedWg.vpnActiveStatus.text =
                        "An error occurred: \n No Wi-Fi or mobile data enabled."
                    2 -> binding.fragmentVpnIncludedWg.vpnActiveStatus.text =
                        "An error occurred: \n Wi-Fi or mobile data enabled, but no Internet access."
                    3 -> binding.fragmentVpnIncludedWg.vpnActiveStatus.text =
                        "An error occurred: \n Unable to reach the endpoint due to the absence of IPv6 support from your current ISP.\n"
                }

                binding.fragmentVpnIncludedWg.vpnStatusTitle.text = "DISCONNECTED"
                binding.fragmentVpnIncludedWg.vpnActiveStatus.visibility = View.VISIBLE
                binding.fragmentVpnIncludedWg.vpnActiveSlide.setCompleted(completed=false, true)
            }
        }
        /*** END - WIREGUARD FRAGMENT ***/

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

                if (hostnameEditText.text.toString() != "") {
                    println("SETTING AS WRITING...")
                    /*Sync the new hostname with the router*/
                    setDDNSConfiguration(setDDNSHostname, hostnameEditText.text.toString())
                    setDDNSConfiguration(setDDNSDomain, hostnameEditText.text.toString())

                    /*Add configuration on database*/
                    dbHandler.updateConfiguration("is_ddns_set", "1")
                    dbHandler.addConfiguration("lastDDNS", hostnameEditText.text.toString())
                    dbHandler.updateConfiguration("lastDDNS", hostnameEditText.text.toString())

                    /*Update the initial value*/
                    hostnameInitialText = hostnameEditText.text.toString()
                }
                else{
                    val hostname = "N/D"
                    /*Sync the new hostname with the router*/
                    setDDNSConfiguration(setDDNSHostname, hostname)
                    setDDNSConfiguration(setDDNSDomain, hostname)

                    /*Add configuration on database*/
                    dbHandler.updateConfiguration("is_ddns_set", "0")
                    dbHandler.addConfiguration("lastDDNS", hostname)
                    dbHandler.updateConfiguration("lastDDNS", hostname)

                    /*Update the initial value*/
                    hostnameInitialText = hostname

                    hostnameEditText.setText(hostname)
                }


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

        binding.fragmentPortForwardingIncluded.addRuleImage.setOnClickListener{
            addNewPortForwardingCard(lastIPv6Data)
        }
        /*** END - PORT FORWARDING FRAGMENT ***/

        /*** START - SUPPORT FRAGMENT ***/
        /*Applying effect to Support buttons*/
        binding.supportSectionText.setOnTouchListener { view, motionEvent ->
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
        binding.supportBtn.setOnTouchListener { view, motionEvent ->
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

        /*Binding Support buttons*/
        binding.supportSectionText.setOnClickListener {
            /*Handle its view visibility*/
            binding.homeMainLayout.visibility = View.GONE
            binding.fragmentSupportIncluded.root.visibility = View.VISIBLE

            /*Pause main call API*/
            canCallHomeAPi = false

        }
        binding.supportBtn.setOnClickListener {
            /*Handle its view visibility*/
            binding.homeMainLayout.visibility = View.GONE
            binding.fragmentSupportIncluded.root.visibility = View.VISIBLE

            /*Pause main call API*/
            canCallHomeAPi = false

        }

        /*Binding Support exit info button*/
        binding.fragmentSupportIncluded.exitImage.setOnClickListener {
            binding.homeMainLayout.visibility = View.VISIBLE
            binding.fragmentSupportIncluded.root.visibility = View.GONE

            /*Pause main call API*/
            canCallHomeAPi = true

        }
        /*** END - SUPPORT FRAGMENT ***/

        /*** START - SETTINGS FRAGMENT ***/
        /*Applying effect to Settings buttons*/
        binding.settingsText.setOnTouchListener { view, motionEvent ->
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
        binding.settingsBtn.setOnTouchListener { view, motionEvent ->
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

        /*Binding Settings buttons*/
        binding.settingsText.setOnClickListener {
            if (isBoardReachable) {

                /*Handle its view visibility*/
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentSettingsIncluded.root.visibility = View.VISIBLE

                /*Pause main call API*/
                canCallHomeAPi = false

                /*Update luci username*/
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.cardTitle.text =
                    "Username"
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.cardDescription.setText(
                    dbHandler.getConfiguration("luci_username")
                )

                /*Update luci password*/
                binding.fragmentSettingsIncluded.cardLayoutRouterPassword.cardTitle.text =
                    "Password"
                val password = dbHandler.getConfiguration("luci_password")
                println("current luci password is $password")
                if (password.isNullOrEmpty()) {
                    binding.fragmentSettingsIncluded.cardLayoutRouterPassword.cardDescription.setText(
                        "N/D"
                    )
                } else {
                    binding.fragmentSettingsIncluded.cardLayoutRouterPassword.cardDescription.setText(
                        password
                    )
                }

                binding.fragmentSettingsIncluded.wirelessTitle.alpha = 1F
                binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.root.alpha = 1F
                binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.root.alpha = 1F

                binding.fragmentSettingsIncluded.routerRebootSlide.alpha = 1F
                binding.fragmentSettingsIncluded.routerRebootSlide.isLocked = false
                binding.fragmentSettingsIncluded.routerFactoryResetSlide.alpha = 1F
                binding.fragmentSettingsIncluded.routerFactoryResetSlide.isLocked = false

                /*Update resources*/
                callOpenWRTSettings()
            }
            else {
                Log.d("Settings handler","Clicking settings text")
                /*Handle its view visibility*/
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentSettingsIncluded.root.visibility = View.VISIBLE

                /*Update luci username*/
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.cardTitle.text =
                    "Username"
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.cardDescription.setText(
                    dbHandler.getConfiguration("luci_username")
                )

                /*Update luci password*/
                binding.fragmentSettingsIncluded.cardLayoutRouterPassword.cardTitle.text =
                    "Password"
                val password = dbHandler.getConfiguration("luci_password")
                println("current luci password is $password")
                if (password.isNullOrEmpty()) {
                    binding.fragmentSettingsIncluded.cardLayoutRouterPassword.cardDescription.setText(
                        "N/D"
                    )
                } else {
                    binding.fragmentSettingsIncluded.cardLayoutRouterPassword.cardDescription.setText(
                        password
                    )
                }

                binding.fragmentSettingsIncluded.wirelessTitle.alpha = 0.5F
                binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.root.alpha = 0.5F
                binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.cardDescription.setText("N/D")
                binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.cardTitle.text = "SSID"
                binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.cardTitle.text = "Password"
                binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.root.alpha = 0.5F
                binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.cardDescription.setText("N/D")

                binding.fragmentSettingsIncluded.routerRebootSlide.alpha = 0.3F
                binding.fragmentSettingsIncluded.routerRebootSlide.isLocked = true
                binding.fragmentSettingsIncluded.routerFactoryResetSlide.alpha = 0.3F
                binding.fragmentSettingsIncluded.routerFactoryResetSlide.isLocked = true

            }
        }
        binding.settingsBtn.setOnClickListener {
            Log.d("Settings handler","Clicking settings button")
            if (isBoardReachable) {

                /*Handle its view visibility*/
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentSettingsIncluded.root.visibility = View.VISIBLE

                /*Pause main call API*/
                canCallHomeAPi = false

                /*Update luci username*/
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.cardTitle.text =
                    "Username"
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.cardDescription.setText(
                    dbHandler.getConfiguration("luci_username")
                )

                /*Update luci password*/
                binding.fragmentSettingsIncluded.cardLayoutRouterPassword.cardTitle.text =
                    "Password"
                binding.fragmentSettingsIncluded.cardLayoutRouterPassword.cardDescription.setText(
                    dbHandler.getConfiguration("luci_password")
                )

                /*Update resources*/
                callOpenWRTSettings()
            }
            else {
                /*Handle its view visibility*/
                binding.homeMainLayout.visibility = View.GONE
                binding.fragmentSettingsIncluded.root.visibility = View.VISIBLE

                /*Update luci username*/
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.cardTitle.text =
                    "Username"
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.cardDescription.setText(
                    dbHandler.getConfiguration("luci_username")
                )

                /*Update luci password*/
                binding.fragmentSettingsIncluded.cardLayoutRouterPassword.cardTitle.text =
                    "Password"
                binding.fragmentSettingsIncluded.cardLayoutRouterPassword.cardDescription.setText(
                    dbHandler.getConfiguration("luci_password")
                )

                binding.fragmentSettingsIncluded.wirelessTitle.alpha = 0.5F
                binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.root.alpha = 0.5F
                binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.cardDescription.setText("N/D")
                binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.cardTitle.text = "SSID"
                binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.cardTitle.text = "Password"
                binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.root.alpha = 0.5F
                binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.cardDescription.setText("N/D")

                binding.fragmentSettingsIncluded.routerRebootSlide.alpha = 0.3F
                binding.fragmentSettingsIncluded.routerRebootSlide.isLocked = true
                binding.fragmentSettingsIncluded.routerFactoryResetSlide.alpha = 0.3F
                binding.fragmentSettingsIncluded.routerFactoryResetSlide.isLocked = true

            }
        }

        /*Binding Support exit info button*/
        binding.fragmentSettingsIncluded.exitImage.setOnClickListener {
            binding.homeMainLayout.visibility = View.VISIBLE
            binding.fragmentSettingsIncluded.root.visibility = View.GONE

            /*Pause main call API*/
            canCallHomeAPi = true

        }

        /*Binding Wireless SSID pencil, check and close button*/
        val ssidEditText = binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.cardDescription
        binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.editValueImage.setOnClickListener {

            if (isSSIDEditing) {
                /*Let the close button be visible*/
                binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.closeValueImage.visibility = View.GONE

                // When editing is complete
                ssidEditText.isFocusable = false
                ssidEditText.isFocusableInTouchMode = false
                ssidEditText.isClickable = false
                ssidEditText.clearFocus()

                // Hide the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(ssidEditText.windowToken, 0)

                // Change the icon to pencil
                binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.editValueImage.setImageResource(R.drawable.pen)

                /*Sync the new Password with the router*/
                setWirelessConfiguration(setWirelessSSIDCommand,ssidEditText.text.toString())

                /*Update the initial value*/
                ssidInitialText = ssidEditText.text.toString()
                Log.d("OpenWRT","Setting ssidInitalText as $ssidInitialText")


            } else {
                /*Update the initial value*/
                ssidInitialText = ssidEditText.text.toString()

                /*Let the close button be visible*/
                binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.closeValueImage.visibility = View.VISIBLE

                // When editing is enabled
                ssidEditText.isFocusable = true
                ssidEditText.isFocusableInTouchMode = true
                ssidEditText.isClickable = true
                ssidEditText.requestFocus()

                // Optionally, show the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(ssidEditText, InputMethodManager.SHOW_IMPLICIT)

                // Change the icon to check
                binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.editValueImage.setImageResource(R.drawable.check)
            }

            // Toggle the state
            isSSIDEditing = !isSSIDEditing
        }
        binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.closeValueImage.setOnClickListener {
            ssidEditText.setText(ssidInitialText)

            /*Let the close button be visible*/
            binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.closeValueImage.visibility = View.GONE

            /*When editing is complete*/
            ssidEditText.isFocusable = false
            ssidEditText.isFocusableInTouchMode = false
            ssidEditText.isClickable = false
            ssidEditText.clearFocus()

            /*Hide the keyboard*/
            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(ssidEditText.windowToken, 0)

            /*Change the icon to pencil*/
            binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.editValueImage.setImageResource(R.drawable.pen)

            isSSIDEditing = false
        }

        /*Binding Password SSID pencil, check and close button*/
        val wirelessPasswordEditText = binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.cardDescription
        binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.editValueImage.setOnClickListener {

            if (isWirelessPasswordEditing) {
                /*Let the close button be visible*/
                binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.closeValueImage.visibility = View.GONE

                // When editing is complete
                wirelessPasswordEditText.isFocusable = false
                wirelessPasswordEditText.isFocusableInTouchMode = false
                wirelessPasswordEditText.isClickable = false
                wirelessPasswordEditText.clearFocus()

                // Hide the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(wirelessPasswordEditText.windowToken, 0)

                // Change the icon to pencil
                binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.editValueImage.setImageResource(R.drawable.pen)

                /*Sync the new Password with the router*/
                setWirelessConfiguration(setWirelessPasswordCommand,wirelessPasswordEditText.text.toString())

                /*Update the initial value*/
                wirelessPasswordInitialText = wirelessPasswordEditText.text.toString()
                Log.d("OpenWRT","Setting wirelessPasswordInitalText as $wirelessPasswordInitialText")


            } else {
                /*Update the initial value*/
                wirelessPasswordInitialText = wirelessPasswordEditText.text.toString()

                /*Let the close button be visible*/
                binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.closeValueImage.visibility = View.VISIBLE

                // When editing is enabled
                wirelessPasswordEditText.isFocusable = true
                wirelessPasswordEditText.isFocusableInTouchMode = true
                wirelessPasswordEditText.isClickable = true
                wirelessPasswordEditText.requestFocus()

                // Optionally, show the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(wirelessPasswordEditText, InputMethodManager.SHOW_IMPLICIT)

                // Change the icon to check
                binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.editValueImage.setImageResource(R.drawable.check)
            }

            // Toggle the state
            isWirelessPasswordEditing = !isWirelessPasswordEditing
        }
        binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.closeValueImage.setOnClickListener {
            wirelessPasswordEditText.setText(wirelessPasswordInitialText)

            /*Let the close button be visible*/
            binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.closeValueImage.visibility = View.GONE

            /*When editing is complete*/
            wirelessPasswordEditText.isFocusable = false
            wirelessPasswordEditText.isFocusableInTouchMode = false
            wirelessPasswordEditText.isClickable = false
            wirelessPasswordEditText.clearFocus()

            /*Hide the keyboard*/
            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(wirelessPasswordEditText.windowToken, 0)

            /*Change the icon to pencil*/
            binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.editValueImage.setImageResource(R.drawable.pen)

            isWirelessPasswordEditing = false
        }

        /*Binding Luci Username pencil, check and close button*/
        val luciUsernameEditText = binding.fragmentSettingsIncluded.cardLayoutRouterUsername.cardDescription
        binding.fragmentSettingsIncluded.cardLayoutRouterUsername.editValueImage.setOnClickListener {

            if (isLuciUsernameEditing) {
                /*Let the close button be visible*/
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.closeValueImage.visibility = View.GONE

                // When editing is complete
                luciUsernameEditText.isFocusable = false
                luciUsernameEditText.isFocusableInTouchMode = false
                luciUsernameEditText.isClickable = false
                luciUsernameEditText.clearFocus()

                // Hide the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(luciUsernameEditText.windowToken, 0)

                // Change the icon to pencil
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.editValueImage.setImageResource(R.drawable.pen)

                /*Update resource on database*/
                dbHandler.updateConfiguration("luci_username",luciUsernameEditText.text.toString())

                /*Update the initial value*/
                luciUsernameInitialText = luciUsernameEditText.text.toString()
                Log.d("OpenWRT","Setting luciUsernameInitialText as $luciUsernameInitialText")

                /*Update OpenWRT Login Object*/
                luciUsername = dbHandler.getConfiguration("luci_username").toString()
                luciPassword = dbHandler.getConfiguration("luci_password").toString()
                openWRTApi = OpenWRTApi(baseUrl, luciUsername, luciPassword)

            } else {
                /*Update the initial value*/
                luciUsernameInitialText = luciUsernameEditText.text.toString()

                /*Let the close button be visible*/
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.closeValueImage.visibility = View.VISIBLE

                // When editing is enabled
                luciUsernameEditText.isFocusable = true
                luciUsernameEditText.isFocusableInTouchMode = true
                luciUsernameEditText.isClickable = true
                luciUsernameEditText.requestFocus()

                // Optionally, show the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(luciUsernameEditText, InputMethodManager.SHOW_IMPLICIT)

                // Change the icon to check
                binding.fragmentSettingsIncluded.cardLayoutRouterUsername.editValueImage.setImageResource(R.drawable.check)
            }

            // Toggle the state
            isLuciUsernameEditing = !isLuciUsernameEditing
        }
        binding.fragmentSettingsIncluded.cardLayoutRouterUsername.closeValueImage.setOnClickListener {
            luciUsernameEditText.setText(luciUsernameInitialText)

            /*Let the close button be visible*/
            binding.fragmentSettingsIncluded.cardLayoutRouterUsername.closeValueImage.visibility = View.GONE

            /*When editing is complete*/
            luciUsernameEditText.isFocusable = false
            luciUsernameEditText.isFocusableInTouchMode = false
            luciUsernameEditText.isClickable = false
            luciUsernameEditText.clearFocus()

            /*Hide the keyboard*/
            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(luciUsernameEditText.windowToken, 0)

            /*Change the icon to pencil*/
            binding.fragmentSettingsIncluded.cardLayoutRouterUsername.editValueImage.setImageResource(R.drawable.pen)

            isLuciUsernameEditing = false
        }

        /*Binding Luci Password pencil, check and close button*/
        val luciPasswordEditText = binding.fragmentSettingsIncluded.cardLayoutRouterPassword.cardDescription
        binding.fragmentSettingsIncluded.cardLayoutRouterPassword.editValueImage.setOnClickListener {

            if (isLuciPasswordEditing) {
                /*Let the close button be visible*/
                binding.fragmentSettingsIncluded.cardLayoutRouterPassword.closeValueImage.visibility = View.GONE

                // When editing is complete
                luciPasswordEditText.isFocusable = false
                luciPasswordEditText.isFocusableInTouchMode = false
                luciPasswordEditText.isClickable = false
                luciPasswordEditText.clearFocus()

                // Hide the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(luciPasswordEditText.windowToken, 0)

                // Change the icon to pencil
                binding.fragmentSettingsIncluded.cardLayoutRouterPassword.editValueImage.setImageResource(R.drawable.pen)

                /*Update resource on database*/
                dbHandler.updateConfiguration("luci_password",luciPasswordEditText.text.toString())

                /*Update the initial value*/
                luciPasswordInitialText = luciPasswordEditText.text.toString()
                Log.d("OpenWRT","Setting luciPasswordInitialText as $luciPasswordInitialText")

                /*Update OpenWRT Login Object*/
                luciUsername = dbHandler.getConfiguration("luci_username").toString()
                luciPassword = dbHandler.getConfiguration("luci_password").toString()
                openWRTApi = OpenWRTApi(baseUrl, luciUsername, luciPassword)

            } else {
                /*Update the initial value*/
                luciPasswordInitialText = luciPasswordEditText.text.toString()

                /*Let the close button be visible*/
                binding.fragmentSettingsIncluded.cardLayoutRouterPassword.closeValueImage.visibility = View.VISIBLE

                // When editing is enabled
                luciPasswordEditText.isFocusable = true
                luciPasswordEditText.isFocusableInTouchMode = true
                luciPasswordEditText.isClickable = true
                luciPasswordEditText.requestFocus()

                // Optionally, show the keyboard
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(luciPasswordEditText, InputMethodManager.SHOW_IMPLICIT)

                // Change the icon to check
                binding.fragmentSettingsIncluded.cardLayoutRouterPassword.editValueImage.setImageResource(R.drawable.check)
            }

            // Toggle the state
            isLuciPasswordEditing = !isLuciPasswordEditing
        }
        binding.fragmentSettingsIncluded.cardLayoutRouterPassword.closeValueImage.setOnClickListener {
            luciPasswordEditText.setText(luciPasswordInitialText)

            /*Let the close button be visible*/
            binding.fragmentSettingsIncluded.cardLayoutRouterPassword.closeValueImage.visibility = View.GONE

            /*When editing is complete*/
            luciPasswordEditText.isFocusable = false
            luciPasswordEditText.isFocusableInTouchMode = false
            luciPasswordEditText.isClickable = false
            luciPasswordEditText.clearFocus()

            /*Hide the keyboard*/
            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(luciPasswordEditText.windowToken, 0)

            /*Change the icon to pencil*/
            binding.fragmentSettingsIncluded.cardLayoutRouterPassword.editValueImage.setImageResource(R.drawable.pen)

            isLuciPasswordEditing = false
        }

        /*Binding Reboot slide button*/
        binding.fragmentSettingsIncluded.routerRebootSlide.onSlideCompleteListener =
            object : SlideToActView.OnSlideCompleteListener {
                override fun onSlideComplete(view: SlideToActView) {
                    Log.d("OpenVPN", "Reboot Slide activation completed.")
                    rebootPiStarlink()
                }
            }

        /*Binding Factory Reset slide button*/
        binding.fragmentSettingsIncluded.routerFactoryResetSlide.onSlideCompleteListener =
            object : SlideToActView.OnSlideCompleteListener {
                override fun onSlideComplete(view: SlideToActView) {
                    Log.d("OpenVPN", "Factory Reset Slide activation completed.")
                    factoryResetPiStarlink()
                }
            }

        /*Binding Textview link for OpenWRT instructions*/
        binding.fragmentSettingsIncluded.textView2.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://openwrt.org/docs/guide-user/troubleshooting/root_password_reset"))
            startActivity(intent)
        }

        /*** STOP - SETTINGS FRAGMENT ***/

        /*Updating resources on time*/
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (canCallHomeAPi) {
                    Log.d("OpenWRT","Calling function from loop")
                    performLoginAndUpdate()
                }
                delay(10000)  // Always delay to keep the loop running
            }
        }

        /*Ask notification permission*/
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (activity?.let { ContextCompat.checkSelfPermission(it, Manifest.permission.POST_NOTIFICATIONS) } != PackageManager.PERMISSION_GRANTED) {
                // Request the POST_NOTIFICATIONS permission
                activity?.let {
                    ActivityCompat.requestPermissions(
                        it,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST_CODE
                    )
                }
            }
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null

        /*Disconnecting VPN and unbind its service*/
        disconnectVPN()
        unbindService()

        /*Detach database*/
        dbHandler.close()
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

        /*Check if OpenVPN For Android is installed*/
        val isInstalled = isAppInstalled("de.blinkt.openvpn")
        if(!isInstalled){
            binding.layoutVpn.root.visibility = View.VISIBLE
            binding.layoutVpn.cardTitle.text = "VPN"
            val text = "<a href=\"https://play.google.com/store/apps/details?id=de.blinkt.openvpn\">OpenVPN For Android</a> is not installed."
            binding.layoutVpn.cardDescription.text = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
            binding.layoutVpn.root.setOnClickListener(){
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=de.blinkt.openvpn"))
                startActivity(webIntent)
            }
        }
        else{
            binding.layoutVpn.root.visibility = View.GONE
            //bindService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        /*Disconnect VPN in case of Destroying*/
        disconnectVPN()
        //unbindService()
    }

    /*Reboot Pi Starlink*/
    private fun rebootPiStarlink() {
        openWRTApi.executeCommand(
            restartRouterCommand,
            luciToken,
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d(openWRTTag, response.toString())
                    binding.fragmentSettingsIncluded.routerRebootSlide.setCompleted(completed=false,true)
                }
            },
            onFailure = { error, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Error: $error")
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    binding.fragmentSettingsIncluded.routerRebootSlide.setCompleted(completed=false,true)
                }
            }
        )
    }

    /*Reboot Pi Starlink*/
    private fun factoryResetPiStarlink() {
        openWRTApi.executeCommand(
            factoryResetCommand,
            luciToken,
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    val resultString = response.optString("result").trim()
                    Log.d(openWRTTag, resultString)
                    if (resultString == "OK"){
                        Log.d(openWRTTag,"I'm going to delete all OpenVPN For Android profiles.")
                        val lastUUID = dbHandler.getConfiguration("lastVPNUUID")
                        if (lastUUID != null) {
                            val statusDelete = deleteVPNProfile(lastUUID)
                            if (statusDelete) {
                                Log.d(
                                    "VPN-Handler",
                                    "The OpenVPN profile has been deleted"
                                )

                                Log.d(openWRTTag,"I'm going to reset the last DDNS.")
                                dbHandler.updateConfiguration("lastDDNS", "N/D")
                            }
                        }
                    }
                    binding.fragmentSettingsIncluded.routerFactoryResetSlide.setCompleted(completed=false,true)
                }
            },
            onFailure = { error, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Error: $error")
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    binding.fragmentSettingsIncluded.routerFactoryResetSlide.setCompleted(completed=false,true)
                }
            }
        )
    }

    /* Performs login in order to retrieve the auth token */
    private fun performLoginAndUpdate(firstCall: Boolean = false) {
        Log.d("OpenWRT Login", "Performing login and updating resources")
        openWRTApi.login(
            onSuccess = { success ->
                /* Handle successful login, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("OpenWRT", success.toString())

                    /* Check if "result" is not null */
                    val result = success.optString("result", "null")
                    if (result != "null") {
                        /* Update the token */
                        luciToken = result

                        /*LuciToken has been retrieved, calling the API to get IPv6 address */
                        callOpenWRTIPApi()

                        /* Update the board status as online */
                        updateBoardStatus(true)

                        /*
                        Check if the VPN is already active and the board is reachable
                        We address the scenario where we've lost its last child process but it's still active
                        */
                        val isVPNConnected = context?.let { isVpnActive(it) }


                        /* Fetch UUID*/
                        val uuid = dbHandler.getConfiguration("lastUUID")
                        Log.d("VPN-Handler","VPN is connected: $isVPNConnected with uuid: $uuid")

                        /*VPN is connected*/
                        if (isVPNConnected == true){

                            /*Check acquired IPv4*/
                            val acquiredIPAddress = getVpnIPv4Address()
                            if (acquiredIPAddress != null){

                                /*Wireguard is connected*/
                                if (acquiredIPAddress == "192.168.8.2"){
                                    isWireguardConnected = true
                                    updateWireguardActivationSliderStatus(true)
                                }

                                else{

                                    /*UUID is not null*/
                                    if (uuid != null) {

                                        /*OpenVPN is connected*/
                                        if (acquiredIPAddress == "192.168.9.2"){
                                            isVpnConnected = true
                                            updateActivationSliderStatus(true)
                                        }
                                    }
                                }

                            }

                        }

                        if (firstCall) {
                            /* Set variable to true */
                            canCallHomeAPi = true
                        }
                    } else {
                        Log.d("OpenWRT Login", "Failed to retrieve luciToken: 'result' is null")
                        /* Handle the case where "result" is null */
                        updateBoardStatus(false)

                        /*Update resources*/
                        binding.layoutDns.cardDescription.text = "Failed to log into StarlinuX. Please check your credentials in Settings."
                    }
                }
            },
            onFailure = { error ->
                /* Handle login failure, update UI on the main thread */
                activity?.runOnUiThread {
                    /* Update the board status as disconnected */
                    updateBoardStatus(false)
                    Log.d("OpenWRT", "OpenWRT Error: $error")
                }

                // Optional: Retry after a delay or log and allow the loop to retry in the next iteration
                CoroutineScope(Dispatchers.IO).launch {
                    delay(5000)  // Wait for 5 seconds before retrying (optional)
                    performLoginAndUpdate(firstCall)
                }
            }
        )
    }

    fun getVpnIPv4Address(): String? {
        try {
            // Get all network interfaces on the device
            val interfaces = NetworkInterface.getNetworkInterfaces()

            // Loop through the interfaces to find the active VPN interface
            for (networkInterface in Collections.list(interfaces)) {
                // Check if the network interface is up, is a point-to-point connection (common for VPNs), and is not a loopback interface
                if (networkInterface.isUp && networkInterface.isPointToPoint && !networkInterface.isLoopback) {
                    // Get the addresses associated with this interface
                    val addresses = networkInterface.inetAddresses

                    // Look for an IPv4 address
                    for (inetAddress in Collections.list(addresses)) {
                        if (inetAddress is Inet4Address) {
                            // Return the IPv4 address as a string
                            return inetAddress.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Return null if no VPN IPv4 address is found
        return null
    }

    /*Updates the board status resources*/
    private fun updateBoardStatus(isOnline: Boolean) {
        isBoardReachable = isOnline

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

        /*Settings*/
        //binding.settingsText.alpha = alphaValue
        //binding.settingsImageIcon.alpha = alphaValue
        //binding.settingsBtn.alpha = alphaValue

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
            binding.layoutDns.cardDescription.text = "Your StarlinuX is unreachable, connect to Wi-Fi or through VPN."
            context?.let { ContextCompat.getColor(it, R.color.teal_900) }
                ?.let { binding.layoutDns.root.setBackgroundColor(it) }
            binding.layoutDns.contentLayout.setBackgroundResource(R.drawable.border)
        }
    }

    /* Call the OpenWRT in order to get system board information*/
    private fun callOpenWRTApi() {
        openWRTApi.executeCommand(
            systemBoardCommand,
            luciToken,
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d(openWRTTag, response.toString())
                    updateSystemBoardInformation(response)
                }
            },
            onFailure = { error, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Error: $error")
                    Log.d(openWRTTag, "Response Code: $responseCode")
                }
            }
        )
    }

    /* Call the OpenWRT in order to get storage and cache board information*/
    private fun callOpenWRTStorageApi() {
        openWRTApi.executeCommand(
            systemBoardInformationCommand,
            luciToken,
            onSuccess = { response, responseCode ->
                /*Handle the successful response, update UI on the main thread*/
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d(openWRTTag, response.toString())

                    /*Update resource information*/
                    updateSystemStorageInformation(response)
                }
            },
            onFailure = { error, responseCode ->
                /*Handle the failure, update UI on the main thread*/
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Error: $error")
                    Log.d(openWRTTag, "Response Code: $responseCode")
                }
            }
        )
    }

    /* Call the OpenWRT in order to get the IPv6 address */
    private fun callOpenWRTIPApi() {
        openWRTApi.executeCommand(
            getIPv6addressCommand,
            luciToken,
            onSuccess = { response, responseCode ->
                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d(openWRTTag, response.toString())

                    updateIPStatus(response)
                }
            },
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Error: $error")
                    Log.d(openWRTTag, "Response Code: $responseCode")
                }
            }
        )
    }

    /* Call OpenWRT in order to get network information */
    private fun callOpenWRTNetwork(onResult: (JSONObject?) -> Unit) {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            onResult(null)
            return
        }

        openWRTApi.executeCommand(
            getConnectedDevices,
            luciToken,
            onSuccess = { response, responseCode ->
                /* Handle the successful response */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
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
            onFailure = { error, responseCode ->
                /* Handle the failure */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Error: $error")
                    Log.d(openWRTTag, "Response Code: $responseCode")
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
                    hostname = if (leaseObject.has("hostname")) leaseObject.getString("hostname") else "N/D",
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
                val lease6 = lease6Object?.getString("ip6addr")?.let { it ->
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
            onSuccess = { response, responseCode ->
                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
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

                    /* Get DHCPv6 data */
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
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Error: $error")
                    Log.d(openWRTTag, "Response Code: $responseCode")
                }
            }
        )
    }

    /* Call the OpenWRT in order to get port forwarding rules */
    private fun callOpenWRTSettings() {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")

            /*Update resource*/
            binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.cardDescription.setText("Failed to retrieve, check router credentials.")
            binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.cardTitle.text = "SSID"

            binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.cardDescription.setText("Failed to retrieve, check router credentials.")
            binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.cardTitle.text = "Password"
            return
        }

        openWRTApi.executeCommand(
            getWirelessConfigCommand,
            luciToken,
            onSuccess = { response, responseCode ->
                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d(openWRTTag, response.toString())

                    /* Parsing the JSON Object */
                    try {
                        val resultString = response.optString("result")

                        Log.d("OpenWRT", "Result String: $resultString")

                        // Parse the resultString as JSON
                        val resultJson = JSONObject(resultString)

                        // Extract ssid and key
                        val ssid = resultJson.optString("ssid")
                        val key = resultJson.optString("key")

                        /*Update SSID resources*/
                        binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.cardTitle.text = "SSID"
                        binding.fragmentSettingsIncluded.cardLayoutWirelessSsid.cardDescription.setText(ssid)

                        /*Update Wireless Password resources*/
                        binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.cardTitle.text = "Password"
                        binding.fragmentSettingsIncluded.cardLayoutWirelessPassword.cardDescription.setText(key)

                    } catch (e: Exception) {
                        e.printStackTrace()
                        Log.e("OpenWRT", "Error parsing response", e)
                    }
                }
            },
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
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
            onSuccess = { response, responseCode ->
                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
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
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d(openWRTTag, error)
                }
            }
        )
    }

    /* Get VPN connected devices */
    private fun getWireguardConnectedDevices() {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }

        openWRTApi.executeCommand(
            getWireguardConnectedDevicesCommand,
            luciToken,
            onSuccess = { response, responseCode ->
                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d(openWRTTag, response.toString())

                    /* Emptying the cards */
                    wgVpnDevices.clear()
                    val parentLayout = view?.findViewById<LinearLayout>(R.id.vpn_card_container_wg)
                    parentLayout?.removeAllViews()

                    /* Parsing the JSON Object */
                    try {
                        val resultString = response.optString("result")
                        Log.d("Wireguard", "resultString: $resultString")

                        val jsonObject = JSONObject(resultString)

                        val vpnArray: JSONArray = jsonObject.optJSONArray("ROUTING TABLE") ?: JSONArray()

                        if (vpnArray.length() == 0) {
                            Log.d("Wireguard", "ROUTING TABLE array is empty")
                            binding.fragmentVpnIncludedWg.noConnectedDevice.visibility = View.VISIBLE
                        } else {
                            binding.fragmentVpnIncludedWg.noConnectedDevice.visibility = View.GONE
                            for (i in 0 until vpnArray.length()) {
                                val leaseObject = vpnArray.getJSONObject(i)

                                val commonName = leaseObject.optString("Common Name", "Unknown")
                                val virtualAddress = leaseObject.optString("IPv4 Address", "Unknown")
                                val realAddress = leaseObject.optString("IPv6 Address", "Unknown")

                                val lease = DhcpLease(
                                    hostname = commonName,
                                    ipaddr = virtualAddress,
                                    macAddr = realAddress,
                                )
                                wgVpnDevices.add(lease)
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
                    addWgVpnCards()
                }
            },
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
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
            Log.d("VPN-Configuration","Preventing action, can't active slider due to its locked status.")
            return
        }
        Log.d("VPN-Configuration","Calling the API for setting VPN...")
        openWRTApi.executeCommand(
            setupVPNCommand,
            luciToken,
            onSuccess = { response, responseCode ->
                Log.d(openWRTTag, "Response Code: $responseCode")

                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    val resultString = response.optString("result").trim()

                    Log.d("VPN-Configuration","Config result is $resultString")
                    if (resultString == "OK"){

                        /*Configuration process started*/
                        Log.d("VPN-Configuration","VPN configuration process has been started.")
                        trigVpnConfigResourceStatus(1)
                    }
                }
            },
            onFailure = { error, responseCode ->
                Log.d(openWRTTag, "Response Code: $responseCode")

                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = false,false)
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /* Setup WireguardVPN */
    private fun setupWireguardVPN() {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }
        if (binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.isLocked){
            Log.d("Wireguard-Configuration","Preventing action, can't active slider due to its locked status.")
            return
        }
        Log.d("Wireguard-Configuration","Calling the API for setting Wireguard VPN...")
        openWRTApi.executeCommand(
            setupWireguardCommand,
            luciToken,
            onSuccess = { response, responseCode ->
                Log.d(openWRTTag, "Response Code: $responseCode")

                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    val resultString = response.optString("result").trim()

                    Log.d("Wireguard-Configuration","Config result is $resultString")
                    if (resultString == "OK"){

                        /*Configuration process started*/
                        Log.d("Wireguard-Configuration","VPN configuration process has been started.")
                        trigWireguardVpnConfigResourceStatus(1)
                    }
                }
            },
            onFailure = { error, responseCode ->
                Log.d(openWRTTag, "Response Code: $responseCode")

                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.setCompleted(completed = false,false)
                    Log.d("Wireguard", error)
                }
            }
        )
    }

    /*Trig the vpn status resources*/
    private fun trigVpnConfigResourceStatus(status: Int){
        if (status == 1) {
            binding.fragmentVpnIncluded.vpnConfigStatus.text =
                "VPN configuration will take some time. Please come back here in a few minutes."
            binding.fragmentVpnIncluded.setupTitle.text = "Configuration: IN PROGRESS"
            binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
            binding.fragmentVpnIncluded.vpnConfigActiveSlide.alpha = 0.5F
            binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.GONE
        }
    }

    /*Trig the Wireguard VPN status resources*/
    private fun trigWireguardVpnConfigResourceStatus(status: Int){
        if (status == 1) {
            binding.fragmentVpnIncludedWg.vpnConfigStatus.text =
                "VPN configuration will take some time. Please come back here in a few minutes."
            binding.fragmentVpnIncludedWg.setupTitle.text = "Configuration: IN PROGRESS"
            binding.fragmentVpnIncludedWg.vpnConfigStatus.visibility = View.VISIBLE
            binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.alpha = 0.5F
            binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.visibility = View.GONE
        }
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
            onSuccess = { response, responseCode ->
                Log.d(openWRTTag, "Response Code: $responseCode")

                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("VPN-Configuration", "Answer is: $response")
                    val resultString = response.optString("result").trim()
                    if (resultString == "1"){

                        /* Handle resources in case of configuration still in process */
                        Log.d("VPN-Configuration","VPN configuration is still in progress.")
                        trigVpnConfigResourceStatus(1)

                        /*Update resources*/
                        binding.fragmentVpnIncluded.vpnConfigActiveSlide.isLocked = true
                        binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.GONE
                        binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = true,false)
                    }
                    else{
                        if (resultString == "2"){

                            /* Handle resources in case of configuration done */
                            Log.d("VPN-Configuration","VPN configuration done!")
                            binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
                            binding.fragmentVpnIncluded.vpnConfigStatus.text = "VPN configuration is complete, use the slider below to connect."
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.isLocked = true
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.GONE
                            binding.fragmentVpnIncluded.setupTitle.text = "Configuration: COMPLETED"

                            /*Trying to fetch the configuration file*/
                            Log.d("VPN-Configuration","Fetching the new VPN profile from Pi Starlink")
                            fetchVPNConfiguration(client = "admin")
                        }
                        if (resultString == "0"){
                            /* Handle resources in case of configuration in idle */
                            Log.d("VPN-Configuration","VPN configuration is in idle.")
                            binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.GONE
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.alpha = 1F
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.VISIBLE
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.isLocked = true
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = false,true)
                            binding.fragmentVpnIncluded.setupTitle.text = "Configuration"
                            binding.fragmentVpnIncluded.vpnConfigActiveSlide.isLocked = false
                        }
                    }
                }
            },
            onFailure = { error, responseCode ->
                Log.d(openWRTTag, "Response Code: $responseCode")
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /* Check WireguardVPN config status */
    private fun checkWireguardVPNConfigStatus() {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }

        openWRTApi.executeCommand(
            checkWireguardConfigStatusCommand,
            luciToken,
            onSuccess = { response, responseCode ->
                Log.d(openWRTTag, "Response Code: $responseCode")

                /* Handle the successful response, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("Wireguard-Configuration", "Answer is: $response")
                    val resultString = response.optString("result").trim()
                    if (resultString == "1"){

                        /* Handle resources in case of configuration still in process */
                        Log.d("Wireguard-Configuration","VPN configuration is still in progress.")
                        trigWireguardVpnConfigResourceStatus(1)

                        /*Update resources*/
                        binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.isLocked = true
                        binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.visibility = View.GONE
                        binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.setCompleted(completed = true,false)
                    }
                    else{
                        if (resultString == "2"){

                            /* Handle resources in case of configuration done */
                            Log.d("Wireguard-Configuration","VPN configuration done!")
                            binding.fragmentVpnIncludedWg.vpnConfigStatus.visibility = View.VISIBLE
                            binding.fragmentVpnIncludedWg.vpnConfigStatus.text = "VPN configuration is complete, use the slider below to connect."
                            binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.isLocked = true
                            binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.visibility = View.GONE
                            binding.fragmentVpnIncludedWg.setupTitle.text = "Configuration: COMPLETED"

                            /*Trying to fetch the configuration file*/
                            Log.d("Wireguard-Configuration","Fetching the new VPN profile from Pi Starlink")
                            fetchWireguardConfiguration(client = "admin")
                        }
                        if (resultString == "0"){
                            /* Handle resources in case of configuration in idle */
                            Log.d("Wireguard-Configuration","VPN configuration is in idle.")
                            binding.fragmentVpnIncludedWg.vpnConfigStatus.visibility = View.GONE
                            binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.alpha = 1F
                            binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.visibility = View.VISIBLE
                            binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.isLocked = true
                            binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.setCompleted(completed = false,true)
                            binding.fragmentVpnIncludedWg.setupTitle.text = "Configuration"
                            binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.isLocked = false
                        }
                    }
                }
            },
            onFailure = { error, responseCode ->
                Log.d(openWRTTag, "Response Code: $responseCode")
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("Wireguard", error)
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
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {

                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("VPN-Configuration", response.toString())
                    val resultString = response.optString("result").trim()

                    /*Check if the resultString contains the file content*/
                    if (resultString.isNotEmpty()) {

                        /*Initialize FileHelper with context*/
                        val fileHelper = FileHelper(activity!!)
                        fileHelper.saveFileToInternalStorage(resultString, "$client.ovpn")
                        Log.d("VPN-Configuration","Config file has been successfully saved.")

                        /*Adding the admin profile to OpenVPN for Android*/
                        addNewOpenvpnProfile(client="admin")

                    } else {
                        Log.w("VPN-Configuration", "No content found in the response.")
                    }
                }
            },
            onFailure = { error, responseCode ->
                Log.d(openWRTTag, "Response Code: $responseCode")
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Fetch vpn configuration*/
    private fun fetchWireguardConfiguration(client: String) {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }

        openWRTApi.executeCommand(
            "$fetchWireguardConfigurationFileCommand$client.conf",
            luciToken,
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {

                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("Wireguard-Configuration", response.toString())
                    val resultString = response.optString("result").trim()

                    /*Check if the resultString contains the file content*/
                    if (resultString.isNotEmpty()) {

                        /*Initialize FileHelper with context*/
                        //val fileHelper = FileHelper(activity!!)
                        //fileHelper.saveFileToInternalStorage(resultString, "$client.ovpn")
                        Log.d("Wireguard-Configuration","Config file has been successfully saved.")

                        /*Adding the admin profile to OpenVPN for Android*/
                        addNewWireguardProfile(profile=resultString)

                    } else {
                        Log.w("Wireguard-Configuration", "No content found in the response.")
                    }
                }
            },
            onFailure = { error, responseCode ->
                Log.d(openWRTTag, "Response Code: $responseCode")
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d("Wireguard", error)
                }
            }
        )
    }

    /*Fetch VPN configuration locally and return the content*/
    private fun fetchVPNConfigurationLocally(client: String): String? {
        return try {
            // Log the start of the operation
            Log.d(openWRTTag, "Fetching VPN configuration for client: $client locally.")

            // Define the path to the local file
            val fileName = "$client.ovpn"
            val fileHelper = FileHelper(activity!!)
            val resultString = fileHelper.readFileFromInternalStorage(fileName)

            // Check if the file content was retrieved successfully
            if (resultString.isNotEmpty()) {
                Log.d("OpenVPN", "Config file has been successfully read from local storage.")
                resultString
            } else {
                Log.w("OpenVPN", "No content found in the local config file.")
                null
            }
        } catch (e: Exception) {
            Log.e(openWRTTag, "Failed to fetch VPN configuration locally: ${e.message}")
            null
        }
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
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("VPN-Configuration", response.toString())
                    val resultString = response.optString("result").trim()

                    Log.d("VPN-Configuration","Config status file has been successfully edited.")

                    binding.fragmentVpnIncluded.setupTitle.visibility = View.VISIBLE
                    binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
                    binding.fragmentVpnIncluded.setupTitle.text = "CONFIGURATION: COMPLETED"
                    binding.fragmentVpnIncluded.vpnConfigStatus.text = "VPN configuration is complete, use the slider below to connect."

                    /*Insert configuration into database*/
                    val currentTimeStamp = System.currentTimeMillis().toString()
                    dbHandler.addConfiguration("lastVPNSync",currentTimeStamp)
                    dbHandler.updateConfiguration("lastVPNSync",currentTimeStamp)

                    /*Update UUID*/
                    updateVPNLayout()
                }
            },
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Set vpn status to 0*/
    private fun setWireguardVPNConfiguration() {
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }

        openWRTApi.executeCommand(
            setWireguardConfigAsFetched,
            luciToken,
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("Wireguard-Configuration", response.toString())
                    val resultString = response.optString("result").trim()

                    Log.d("Wireguard-Configuration","Config status file has been successfully edited.")

                    binding.fragmentVpnIncluded.setupTitle.visibility = View.VISIBLE
                    binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
                    binding.fragmentVpnIncluded.setupTitle.text = "CONFIGURATION: COMPLETED"
                    binding.fragmentVpnIncluded.vpnConfigStatus.text = "VPN configuration is complete, use the slider below to connect."

                    /*Insert configuration into database*/
                    val currentTimeStamp = System.currentTimeMillis().toString()
                    dbHandler.addConfiguration("lastWireguardVPNSync",currentTimeStamp)
                    dbHandler.updateConfiguration("lastWireguardVPNSync",currentTimeStamp)

                    /*Update UUID*/
                    updateWireguardVPNLayout()
                }
            },
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("Wireguard", error)
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
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("OpenVPN", response.toString())
                    val resultString = response.optString("result").trim()
                    Log.d("OpenVPN","DDNS configuration: $resultString")

                    /*Update resources*/
                    /* Assuming the resultString is in JSON format */
                    try {
                        val jsonResult = JSONObject(resultString)

                        /*Check if DDNS is null*/
                        val hostname = jsonResult.optString("lookup_host")
                        val username = jsonResult.optString("username")
                        val password = jsonResult.optString("password")
                        if (hostname == "N/D"){
                            Log.d("OpenWRT","DDNS is null, setting resources.")
                            dbHandler.updateConfiguration("is_ddns_set", "0")
                            dbHandler.updateConfiguration("lastDDNS", "N/D")
                            binding.layoutDns.contentLayout.setBackgroundResource(R.drawable.border)
                        }
                        else{
                            dbHandler.addConfiguration("is_ddns_set", "1")
                            dbHandler.updateConfiguration("is_ddns_set", "1")

                            dbHandler.addConfiguration("lastDDNS", hostname)
                            dbHandler.updateConfiguration("lastDDNS", hostname)
                        }

                        /* Update resources */
                        /* Hostname */
                        binding.fragmentDdnsIncluded.cardLayoutHostname.cardTitle.text = "Hostname"
                        binding.fragmentDdnsIncluded.cardLayoutHostname.cardDescription.setText(hostname)
                        hostnameInitialText = hostname

                        /* Username */
                        binding.fragmentDdnsIncluded.cardLayoutUsername.cardTitle.text = "Username"
                        binding.fragmentDdnsIncluded.cardLayoutUsername.cardDescription.setText(username)
                        usernameInitialText = username

                        /* Password */
                        binding.fragmentDdnsIncluded.cardLayoutPassword.cardTitle.text = "Password"
                        binding.fragmentDdnsIncluded.cardLayoutPassword.cardDescription.setText(password)
                        passwordInitialText = password

                    } catch (e: JSONException) {
                        Log.e("OpenVPN", "Failed to parse JSON: ${e.message}")
                    }
                }
            },
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
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
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
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
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Set Wireless configuration*/
    private fun setWirelessConfiguration(command: String, value: String){
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }
        val composedCmd = String.format(command, value) + " && " + commitWirelessCommand
        openWRTApi.executeCommand(
            composedCmd,
            luciToken,
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("OpenVPN", response.toString())
                    val resultString = response.optString("result").trim()
                    Log.d("OpenVPN","Wireless configuration has been set: $resultString")

                    /*Update resources*/
                    /* Assuming the resultString is in JSON format */
                    try {
                        val jsonResult = JSONObject(resultString)

                    } catch (e: JSONException) {
                        Log.e("OpenVPN", "Failed to parse JSON: ${e.message}")
                    }
                }
            },
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Add a new Firewall configuration */
    private fun addNewFirewallConfiguration(command: String, name: String){
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }
        val composedCmd = String.format(command, name) + " && " + commitFirewallCommand
        Log.d("OpenWRT","Composed command: $composedCmd")
        openWRTApi.executeCommand(
            composedCmd ,
            luciToken,
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("OpenVPN", response.toString())
                }
            },
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Set Firewall configuration*/
    private fun setFirewallConfiguration(command: String, id: String, value: String, restart : Boolean = false){
        var composedCmd = ""
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }
        composedCmd = if (!restart) {
            String.format(command, id, value) + " && " + commitFirewallCommand
        } else{
            String.format(command, id, value) + " && " + commitFirewallCommand + " && " + restartFirewallCommand
        }
        Log.d("OpenWRT","Composed command: $composedCmd")
        openWRTApi.executeCommand(
            composedCmd ,
            luciToken,
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("OpenVPN", response.toString())
                }
            },
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("OpenVPN", error)
                }
            }
        )
    }

    /*Delete Firewall configuration*/
    private fun deleteFirewallConfiguration(command: String, id: String){
        if (!::luciToken.isInitialized) {
            Log.w(openWRTTag, "luciToken is null or empty. Aborting the operation.")
            return
        }
        val composedCmd = String.format(command, id) + " && " + commitFirewallCommand
        Log.d("OpenWRT","Composed command: $composedCmd")
        openWRTApi.executeCommand(
            composedCmd ,
            luciToken,
            onSuccess = { response, responseCode ->
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("OpenWRT", response.toString())
                }
            },
            onFailure = { error, responseCode ->
                /* Handle the failure, update UI on the main thread */
                activity?.runOnUiThread {
                    Log.d(openWRTTag, "Response Code: $responseCode")
                    Log.d("OpenWRT", error)
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

        /*Check weather DDNS has been set or not*/
        val isDDNSset = dbHandler.getConfiguration("is_ddns_set")
        if (isDDNSset == "0") {
            binding.layoutDns.cardTitle.text = "DDNS"
            binding.layoutDns.cardDescription.text = "You haven't set up a DDNS yet."
            binding.layoutDns.contentLayout.setBackgroundResource(R.drawable.border)
        }
        else{
            val lastDDNS = dbHandler.getConfiguration("lastDDNS")
            println("lastDDNS is $lastDDNS")
            if (lastDDNS != null) {
                if (lastDDNS != "N/D" && lastDDNS != "" ) {
                    binding.layoutDns.cardTitle.text = "DDNS"
                    binding.layoutDns.cardDescription.text = lastDDNS
                    binding.layoutDns.copyIpv4Image.setImageResource(R.drawable.copy)
                    binding.layoutDns.contentLayout.background = null
                }
            }
            else{
                binding.layoutDns.cardTitle.text = "DDNS"
                binding.layoutDns.cardDescription.text = "You haven't set up a DDNS yet."
                binding.layoutDns.contentLayout.setBackgroundResource(R.drawable.border)
            }
        }
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

            /*Binding clipboard IPv4 buttons*/
            cardView.findViewById<ImageView>(R.id.copy_ipv4_image).setOnClickListener {
                val clip = ClipData.newPlainText("Copied Text", lease.ipaddr)
                clipboard.setPrimaryClip(clip)
            }

            /*Binding clipboard Mac Address buttons*/
            cardView.findViewById<ImageView>(R.id.copy_macaddr_image).setOnClickListener {
                val clip = ClipData.newPlainText("Copied Text", lease.macAddr)
                clipboard.setPrimaryClip(clip)
            }
        }

        for (lease in dhcp6Leases) {
            val inflater = LayoutInflater.from(context)
            val cardView = inflater.inflate(R.layout.layout_card_network6, parentLayout6, false)

            cardView.findViewById<TextView>(R.id.card_title_hostname).text = lease.hostname
            cardView.findViewById<TextView>(R.id.card_description_ip_addr).text = lease.ipaddr
            parentLayout6?.addView(cardView)

            /*Binding clipboard IPv6 buttons*/
            cardView.findViewById<ImageView>(R.id.copy_ipv6_image).setOnClickListener {
                val clip = ClipData.newPlainText("Copied Text", lease.ipaddr)
                clipboard.setPrimaryClip(clip)
            }
        }
    }

    /*Adding port forwarding cards at the view based on the array*/
    private fun addPortForwardingCards(dhcp6: JSONObject) {
        lastIPv6Data = dhcp6

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

            /*Switch Button*/
            val switchButton = cardView.findViewById<SwitchButton>(R.id.switch_button)
            switchButton.isChecked = lease.enabled == "1"
            switchButton.setOnCheckedChangeListener { _, isChecked ->
                // Handle the checked state change
                if (isChecked) {
                    setFirewallConfiguration(setPortForwardingEnabledCommand,lease.id,"1", true)
                } else {
                    setFirewallConfiguration(setPortForwardingEnabledCommand,lease.id,"0", true)
                }
            }

            /*External Port*/
            var initialExtPortValue = lease.externalPort
            var isExternalPortEditing = false
            val externalPortEditImage = cardView.findViewById<ImageView>(R.id.edit_external_value_image)
            val externalPortCloseImage = cardView.findViewById<ImageView>(R.id.close_external_value_image)
            val externalPortEditText = cardView.findViewById<EditText>(R.id.external_port_description)
            val cancelRuleImage = cardView.findViewById<ImageView>(R.id.delete_rule)
            externalPortEditText.background.alpha = 0
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
            cancelRuleImage.setOnClickListener {
                deleteFirewallConfiguration(deletePortForwardingRuleCommand,lease.id)
                cardView.visibility = View.GONE
            }

            /*Destination Port*/
            var initialDestPortValue = lease.destinationPort
            var isDestinationPortEditing = false
            val destinationPortEditImage = cardView.findViewById<ImageView>(R.id.edit_destination_value_image)
            val destinationPortCloseImage = cardView.findViewById<ImageView>(R.id.close_destination_value_image)
            val destinationPortEditText = cardView.findViewById<EditText>(R.id.destination_port_description)
            destinationPortEditText.background.alpha = 0
            destinationPortEditImage.setOnClickListener {
                if (isDestinationPortEditing) {
                    destinationPortCloseImage.visibility = View.GONE

                    destinationPortEditText.isFocusable = false
                    destinationPortEditText.isFocusableInTouchMode = false
                    destinationPortEditText.isClickable = false
                    destinationPortEditText.isEnabled = false
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
                    destinationPortEditText.isEnabled = true
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

            /*Spinner setup for target IP addresses*/
            val targetIpSpinner = cardView.findViewById<Spinner>(R.id.target_description)
            val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, ipv6AddressList)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            targetIpSpinner.adapter = adapter

            /*Set the selected IP address if it's available in lease.targetIp*/
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

    /*Adding new port forwarding cards at the view based on the array*/
    private fun addNewPortForwardingCard(dhcp6: JSONObject) {
        val parentLayout = view?.findViewById<LinearLayout>(R.id.pf_card_container)

        /*Assuming `leases6Array` is already defined*/
        val leases6Array: JSONArray = dhcp6.getJSONArray("dhcp6_leases")
        val ipv6Addresses = mutableSetOf<String>()
        for (i in 0 until leases6Array.length()) {
            val leaseObject = leases6Array.getJSONObject(i)

            /*Get the primary IPv6 address*/
            val primaryIp6 = leaseObject.optString("ip6addr")
            if (primaryIp6.isNotEmpty()) {
                ipv6Addresses.add(primaryIp6)
            }
            /*Get the array of additional IPv6 addresses*/
            val ip6addrsArray = leaseObject.optJSONArray("ip6addrs")
            if (ip6addrsArray != null) {
                for (j in 0 until ip6addrsArray.length()) {
                    val ip6addr = ip6addrsArray.getString(j).split("/")[0] // Remove the subnet part
                    ipv6Addresses.add(ip6addr)
                }
            }
        }
        val ipv6AddressList = ipv6Addresses.toList()

        /*Count the existing cards*/
        val currentCardCount = parentLayout?.childCount ?: 0
        val newCardId = currentCardCount + 1

        val inflater = LayoutInflater.from(context)
        val cardView = inflater.inflate(R.layout.layout_card_port_forwarding, parentLayout, false)

        /*Set default values or leave it empty for user input*/
        cardView.findViewById<TextView>(R.id.title_text).text = "Rule $newCardId"
        cardView.findViewById<TextView>(R.id.external_port_description).text = "5555"
        cardView.findViewById<TextView>(R.id.destination_port_description).text = "5555"

        /*Switch Button*/
        val switchButton = cardView.findViewById<SwitchButton>(R.id.switch_button)
        switchButton.isChecked = false
        switchButton.setOnCheckedChangeListener { _, isChecked ->
            // Handle the checked state change
            if (isChecked) {
                setFirewallConfiguration(setPortForwardingEnabledCommand,
                    currentCardCount.toString(),"1", true)
            } else {
                setFirewallConfiguration(setPortForwardingEnabledCommand,
                    currentCardCount.toString(),"0", true)
            }
        }

        /*External Port*/
        var isExternalPortEditing = false
        val externalPortEditImage = cardView.findViewById<ImageView>(R.id.edit_external_value_image)
        val externalPortCloseImage = cardView.findViewById<ImageView>(R.id.close_external_value_image)
        val externalPortEditText = cardView.findViewById<EditText>(R.id.external_port_description)
        val cancelRuleImage = cardView.findViewById<ImageView>(R.id.delete_rule)
        var initialExtPortValue = externalPortEditText.text.toString()
        externalPortEditText.background.alpha = 0
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

                setFirewallConfiguration(setPortForwardingExternalPortCommand,
                    currentCardCount.toString(),initialExtPortValue)

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
        cancelRuleImage.setOnClickListener {
            deleteFirewallConfiguration(deletePortForwardingRuleCommand,
                currentCardCount.toString()
            )
            cardView.visibility = View.GONE
        }

        /*Destination Port*/
        var isDestinationPortEditing = false
        val destinationPortEditImage = cardView.findViewById<ImageView>(R.id.edit_destination_value_image)
        val destinationPortCloseImage = cardView.findViewById<ImageView>(R.id.close_destination_value_image)
        val destinationPortEditText = cardView.findViewById<EditText>(R.id.destination_port_description)
        var initialDestPortValue = destinationPortEditText.text.toString()
        destinationPortEditText.background.alpha = 0
        destinationPortEditImage.setOnClickListener {
            if (isDestinationPortEditing) {
                destinationPortCloseImage.visibility = View.GONE

                destinationPortEditText.isFocusable = false
                destinationPortEditText.isFocusableInTouchMode = false
                destinationPortEditText.isClickable = false
                destinationPortEditText.isEnabled = false
                destinationPortEditText.clearFocus()

                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(destinationPortEditText.windowToken, 0)

                destinationPortEditImage.setImageResource(R.drawable.pen)

                initialDestPortValue = destinationPortEditText.text.toString()

                setFirewallConfiguration(setPortForwardingDestinationPortCommand,
                    currentCardCount.toString(),initialDestPortValue)
            } else {
                destinationPortCloseImage.visibility = View.VISIBLE

                destinationPortEditText.isFocusable = true
                destinationPortEditText.isFocusableInTouchMode = true
                destinationPortEditText.isClickable = true
                destinationPortEditText.isEnabled = true
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

        /*Spinner setup for target IP addresses*/
        val targetIpSpinner = cardView.findViewById<Spinner>(R.id.target_description)
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, ipv6AddressList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        targetIpSpinner.adapter = adapter

        /*Creating a new default rule to backend*/
        addNewFirewallConfiguration(addNewFirewallRuleCommand,"Rule $newCardId")

        /*Binding IPv6 spinner*/
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

                setFirewallConfiguration(setPortForwardingTargetIPCommand,
                    currentCardCount.toString(),selectedItem)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // Handle the case when no item is selected (optional)
            }
        }

        parentLayout?.addView(cardView)
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

            /*Binding clipboard IPv4 button*/
            cardView.findViewById<ImageView>(R.id.copy_ipv4_image).setOnClickListener {
                val clip = ClipData.newPlainText("Copied Text", lease.ipaddr)
                clipboard.setPrimaryClip(clip)
            }

            /*Binding clipboard Mac Address button*/
            cardView.findViewById<ImageView>(R.id.copy_macaddr_image).setOnClickListener {
                val clip = ClipData.newPlainText("Copied Text", lease.macAddr)
                clipboard.setPrimaryClip(clip)
            }
        }

    }

    /*Adding network cards at the view based on the array*/
    private fun addWgVpnCards() {
        val parentLayout = view?.findViewById<LinearLayout>(R.id.vpn_card_container_wg)
        for (lease in wgVpnDevices) {
            val inflater = LayoutInflater.from(context)
            val cardView = inflater.inflate(R.layout.layout_card_network, parentLayout, false)

            cardView.findViewById<TextView>(R.id.card_title_hostname).text = lease.hostname
            cardView.findViewById<TextView>(R.id.card_description_ip_addr).text = lease.ipaddr
            cardView.findViewById<TextView>(R.id.card_description_mac_addr).text = lease.macAddr
            parentLayout?.addView(cardView)

            /*Binding clipboard IPv4 button*/
            cardView.findViewById<ImageView>(R.id.copy_ipv4_image).setOnClickListener {
                val clip = ClipData.newPlainText("Copied Text", lease.ipaddr)
                clipboard.setPrimaryClip(clip)
            }

            /*Binding clipboard Mac Address button*/
            cardView.findViewById<ImageView>(R.id.copy_macaddr_image).setOnClickListener {
                val clip = ClipData.newPlainText("Copied Text", lease.macAddr)
                clipboard.setPrimaryClip(clip)
            }
        }

    }

    /*Updates the VPN Layout base on profile existence*/
    private fun updateVPNLayout() {

        /*Get the list of available VPNs*/
        val vpnList = listVPNs()
        Log.d("VPN-Configuration","VPN list: $vpnList")

        /*Check if the list is empty*/
        if (vpnList.isEmpty()) {
            Log.d("VPN-Configuration", "No VPN profiles found.")

            /*In case the board is unreachable, we must hide the configuration slider*/
            if (!isBoardReachable){
                Log.d("VPN-Configuration","Board is unreachable.")
                binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
                binding.fragmentVpnIncluded.vpnConfigStatus.text = "StarlinuX is unreachable, please connect to its Wi-Fi."
                binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.GONE
            }

            /*In case the board is reachable, show up the configuration slider*/
            else{
                Log.d("VPN-Configuration","Board is reachable, checking the VPN configuration status.")
                binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.VISIBLE
                binding.fragmentVpnIncluded.vpnConfigActiveSlide.isLocked = false

                /*If no profiles have been found, check the configuration status*/
                checkVPNConfigStatus()
            }

            /*Update the activation and configuration sliders*/
            binding.fragmentVpnIncluded.vpnActiveSlide.alpha = 0.5F
            binding.fragmentVpnIncluded.vpnActiveSlide.isLocked = true
            //binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = false,false)


        } else {

            /*Check if the list contains an element with "Pi-Starlink"*/
            val matchingProfile = vpnList.find { it.contains("Pi-Starlink") }

            if (matchingProfile != null) {
                Log.d("OpenVPN", "VPN profiles contain 'Pi-Starlink': $vpnList")

                /*Extract the UUID from the matching profile (assuming the format is "Name:UUID")*/
                profileUUID = matchingProfile.split(":").getOrNull(1)?.trim()

                if (profileUUID != null) {

                    /*Use profileUUID as needed*/
                    Log.d("OpenVPN", "Profile UUID: $profileUUID")

                    /*Insert/Update the UUID into Database*/
                    dbHandler.addConfiguration("lastVPNUUID",profileUUID.toString())
                    dbHandler.updateConfiguration("lastVPNUUID",profileUUID.toString())

                    binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = true, false)
                    binding.fragmentVpnIncluded.vpnActiveSlide.isLocked = false

                    binding.fragmentVpnIncluded.vpnConfigActiveSlide.visibility = View.GONE

                    binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.VISIBLE
                    binding.fragmentVpnIncluded.vpnConfigStatus.text = "VPN configuration is complete, use the slider below to connect."
                    binding.fragmentVpnIncluded.setupTitle.text = "Configuration: COMPLETED"
                    prepareStartProfile(0)

                } else {
                    Log.d("OpenVPN", "No UUID found in the matching profile.")
                }
            } else {
                Log.d("OpenVPN", "VPN profiles do not contain 'Pi-Starlink': $vpnList")
            }
        }
    }

    /*Updates the Wireguard VPN Layout based on profile existence*/
    private fun updateWireguardVPNLayout() {

        /*Get the list of available VPNs*/
        val vpnList = listWireguardVPNs()
        Log.d("Wireguard-Configuration","VPN list: $vpnList")

        /*Check if the list is empty*/
        if (vpnList.isEmpty()) {
            Log.d("Wireguard-Configuration", "No VPN profiles found.")

            /*In case the board is unreachable, we must hide the configuration slider*/
            if (!isBoardReachable){
                Log.d("Wireguard-Configuration","Board is unreachable.")
                binding.fragmentVpnIncludedWg.vpnConfigStatus.visibility = View.VISIBLE
                binding.fragmentVpnIncludedWg.vpnConfigStatus.text = "StarlinuX is unreachable, please connect to its Wi-Fi."
                binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.visibility = View.GONE
            }

            /*In case the board is reachable, show up the configuration slider*/
            else{
                Log.d("Wireguard-Configuration","Board is reachable, checking the VPN configuration status.")
                binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.visibility = View.VISIBLE
                binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.isLocked = false

                /*If no profiles have been found, check the configuration status*/
                checkWireguardVPNConfigStatus()
            }

            /*Update the activation and configuration sliders*/
            binding.fragmentVpnIncludedWg.vpnActiveSlide.alpha = 0.5F
            binding.fragmentVpnIncludedWg.vpnActiveSlide.isLocked = true
            //binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = false,false)

        } else {

            /*Use profileUUID as needed*/
            Log.d("Wireguard", "Profile UUID: $profileUUID")

            binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.setCompleted(completed = true, false)
            binding.fragmentVpnIncludedWg.vpnActiveSlide.isLocked = false

            binding.fragmentVpnIncludedWg.vpnConfigActiveSlide.visibility = View.GONE

            binding.fragmentVpnIncludedWg.vpnConfigStatus.visibility = View.VISIBLE
            binding.fragmentVpnIncludedWg.vpnConfigStatus.text = "VPN configuration is complete, use the slider below to connect."
            binding.fragmentVpnIncludedWg.setupTitle.text = "Configuration: COMPLETED"

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

    /*Adding a new Wireguard profile*/
    private fun addNewWireguardProfile(profile: String) {
        try {
            // Regular expressions to extract each field from the profile string
            val privateKeyRegex = """PrivateKey\s*=\s*(\S+)""".toRegex()
            val addressRegex = """Address\s*=\s*(\S+)""".toRegex()
            val dnsRegex = """DNS\s*=\s*(\S+)""".toRegex()
            val publicKeyRegex = """PublicKey\s*=\s*(\S+)""".toRegex()
            val presharedKeyRegex = """PresharedKey\s*=\s*(\S+)""".toRegex()
            val endpointRegex = """Endpoint\s*=\s*(\S+)""".toRegex()
            val allowedIPsRegex = """AllowedIPs\s*=\s*(\S+)""".toRegex()
            val persistentKeepaliveRegex = """PersistentKeepalive\s*=\s*(\S+)""".toRegex()

            // Function to extract value from regex match
            fun extractValue(regex: Regex): String {
                return regex.find(profile)?.groupValues?.get(1) ?: ""
            }

            // Extract values from the profile string
            val privateKey = extractValue(privateKeyRegex)
            val address = extractValue(addressRegex)
            val dns = extractValue(dnsRegex)
            val publicKey = extractValue(publicKeyRegex)
            val presharedKey = extractValue(presharedKeyRegex)
            val endpoint = extractValue(endpointRegex)
            val allowedIPs = extractValue(allowedIPsRegex)
            val persistentKeepalive = extractValue(persistentKeepaliveRegex)

            dbHandler.addConfiguration("PrivateKey", privateKey)
            dbHandler.addConfiguration("Address", address)
            dbHandler.addConfiguration("DNS", dns)
            dbHandler.addConfiguration("PublicKey", publicKey)
            dbHandler.addConfiguration("PresharedKey", presharedKey)
            dbHandler.addConfiguration("Endpoint", endpoint)
            dbHandler.addConfiguration("AllowedIPs", allowedIPs)
            dbHandler.addConfiguration("PersistentKeepalive", persistentKeepalive)

            dbHandler.updateConfiguration("PrivateKey", privateKey)
            dbHandler.updateConfiguration("Address", address)
            dbHandler.updateConfiguration("DNS", dns)
            dbHandler.updateConfiguration("PublicKey", publicKey)
            dbHandler.updateConfiguration("PresharedKey", presharedKey)
            dbHandler.updateConfiguration("Endpoint", endpoint)
            dbHandler.updateConfiguration("AllowedIPs", allowedIPs)
            dbHandler.updateConfiguration("PersistentKeepalive", persistentKeepalive)

            /*Add the new VPN profile*/
            Log.d("Wireguard", "Profile added: $profile")

            /*Update the VPN layout resources*/
            binding.fragmentVpnIncluded.vpnConfigStatus.visibility = View.GONE
            binding.fragmentVpnIncluded.vpnConfigStatus.alpha = 1F

            binding.fragmentVpnIncluded.vpnConfigActiveSlide.alpha = 1F
            binding.fragmentVpnIncluded.vpnConfigActiveSlide.setCompleted(completed = true,false)

            binding.fragmentVpnIncluded.vpnActiveSlide.alpha = 1F
            binding.fragmentVpnIncluded.vpnActiveSlide.isLocked = false

        } catch (e: IOException) {
            Log.e("Wireguard", "Error reading file: ${e.message}")
        } catch (e: RemoteException) {
            Log.e("Wireguard", "RemoteException: ${e.message}")
        } catch (e: Exception) {
            // Catch any other exceptions to prevent the app from crashing
            Log.e("Wireguard", "Exception: ${e.message}")
        }
        Log.d("Wireguard", "Profile has been started/added")
        setWireguardVPNConfiguration()
    }

    /* Adding a new VPN profile */
    private fun addNewOpenvpnProfileLocally(configString: String): Boolean {
        return try {
            // Ensure the configuration string is not empty
            if (configString.isEmpty()) {
                Log.e("OpenVPN", "Configuration string is empty.")
                return false
            }

            /* Add the new VPN profile */
            val name = profileName
            val profile = mService?.addNewVPNProfile(name, false, configString)
            Log.d("OpenVPN", "Profile added: $profile")

            true
        } catch (e: RemoteException) {
            Log.e("OpenVPN", "RemoteException: ${e.message}")
            false
        } catch (e: Exception) {
            // Catch any other exceptions to prevent the app from crashing
            Log.e("OpenVPN", "Exception: ${e.message}")
            false
        }
    }

    /* Update IPv6 Address in VPNConfig String */
    private fun replaceIPv6Address(configString: String, newIPv6: String): String {
        // Define the regex pattern to match the IPv6 address in the string
        val regex = Regex("""remote\s+\[[0-9a-fA-F:]+\]\s+1194\s+udp""")

        // Replace the matched IPv6 address with the new one
        val updatedString = regex.replace(configString) {
            // Replace the old IPv6 address with the newIPv6 inside the [ ]
            "remote [$newIPv6] 1194 udp"
        }

        // Return the updated string
        return updatedString
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

        activity?.let {
            it.bindService(icsOpenVpnService, mConnection, Context.BIND_AUTO_CREATE)
            Log.d("OpenVPN", "Service has been bound: $mConnection, $mService")
        } ?: run {
            Log.e("OpenVPN", "Activity is null, cannot bind the service.")
        }
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

    /* Returns the list of available Wireguard VPNs */
    private fun listWireguardVPNs(): List<String> {
        val resultList = mutableListOf<String>()

        try {
            // List of required fields for each VPN profile
            val fields = listOf(
                "PrivateKey", "Address", "DNS",
                "PublicKey", "PresharedKey",
                "Endpoint", "AllowedIPs", "PersistentKeepalive"
            )

            // Check if all fields exist in the database
            val vpnExists = fields.all { field ->
                val fieldValue = dbHandler.getConfiguration(field)
                !fieldValue.isNullOrEmpty() // Ensures field exists and is not empty
            }

            if (vpnExists) {
                // If all fields exist, add the VPN identifier (can be 'Address' or any unique field)
                val vpnIdentifier = dbHandler.getConfiguration("Address") ?: "Unknown VPN"
                resultList.add(vpnIdentifier)
            }

        } catch (e: RemoteException) {
            Log.d("OpenVPN", "Error: ${e.message}")
        }

        return resultList
    }

    /*Returns the list of the available VPNs*/
    private fun deleteVPNProfile(profileUuid: String):  Boolean{
        try {
            Log.d("OpenVPN","Deleting OpenVPN profile name: $profileUuid")
            mService?.removeProfile(profileUuid)
        } catch (e: RemoteException) {
            Log.d("OpenVPN", "Error: ${e.message}")
            return  false
        }
        return true
    }

    /*Connects the VPN based on the profile UUID*/
    private fun connectVPN(profile: String = "null") {
        if (profile != "null"){
            profileUUID = profile
        }
        Log.d("OpenVPN", "Attempting to start the OpenVPN profile with UUID: $profileUUID.")

        // Attempt to start the profile and listen for status updates
        try {

            Log.d("OpenVPN","mService: $mService")
            mService?.startProfile(profileUUID)

            /*Update the UUID on db*/
            if(profileUUID != null){
                Log.d("VPN-Handler","Updating uuid with: $profileUUID")
                profileUUID?.let { dbHandler.addConfiguration("lastUUID", it) }
                profileUUID?.let { dbHandler.updateConfiguration("lastUUID", it) }
            }

            // Register callback for receiving status updates
            mService?.registerStatusCallback(object : IOpenVPNStatusCallback.Stub() {
                override fun newStatus(uuid: String, state: String, message: String, level: String) {

                    Log.d("OpenVPN", "New Status - UUID: $uuid, State: $state, Message: $message, Level: $level")

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

                            /*Add or update lastVPNUsed*/
                            dbHandler.addConfiguration("lastVPNUsed","OpenVPN")
                            dbHandler.updateConfiguration("lastVPNUsed","OpenVPN")

                            /*Update the activation slider resource*/
                            updateActivationSliderStatus(true)
                        }
                        if ("No process running" in message){
                            if (isVpnConnected) {
                                Log.e("OpenVPN", "VPN disconnected or interrupted.")
                                updateActivationSliderStatus(false)
                                isVpnConnected = false
                            }
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

    /* Connects to Wireguard Tunnel*/
/*    private fun connectWireguard(){
        Log.d("Wireguard-Handler", "Connecting to Wireguard VPN")
        myVpnService?.connectWireguard()
        myVpnService?.setCallback(this)
    }*/

    private fun connectWireguard() {
        tunnel = WgTunnel()
        val intentPrepare = GoBackend.VpnService.prepare(activity)
        if (intentPrepare != null) {
            startActivityForResult(intentPrepare, 0)
        }
        val interfaceBuilder = Interface.Builder()
        val peerBuilder = Peer.Builder()
        backend = GoBackend(activity)

        val privateKey = dbHandler.getConfiguration("PrivateKey") ?: ""
        val addresses = dbHandler.getConfiguration("Address") ?: ""
        val address1 = addresses.split(",").getOrNull(0)?.trim() ?: ""
        val address2 = addresses.split(",").getOrNull(1)?.trim() ?: ""
        val dns = dbHandler.getConfiguration("DNS") ?: ""
        val publicKey = dbHandler.getConfiguration("PublicKey") ?: ""
        val presharedKey = dbHandler.getConfiguration("PresharedKey") ?: ""
        val endpoint = dbHandler.getConfiguration("Endpoint") ?: ""
        val allowedIPs = dbHandler.getConfiguration("AllowedIPs") ?: "0.0.0.0/0"
        val persistentKeepalive = dbHandler.getConfiguration("PersistentKeepalive")?.toIntOrNull() ?: 25

        AsyncTask.execute {
            try {
                backend.setState(
                    tunnel, Tunnel.State.UP, Config.Builder()
                        .setInterface(
                            interfaceBuilder
                                .addAddress(InetNetwork.parse(address1))  // Address from DB
                                .addAddress(InetNetwork.parse(address2))  // Address from DB
                                .parsePrivateKey(privateKey)  // Private key from DB
                                .addDnsServer(InetAddress.getByName(dns))  // DNS from DB
                                .build()
                        )
                        .addPeer(
                            peerBuilder
                                .addAllowedIp(InetNetwork.parse(allowedIPs))  // Allowed IPs from DB
                                .setEndpoint(InetEndpoint.parse(endpoint))  // Endpoint from DB
                                .parsePublicKey(publicKey)  // Public key from DB
                                .parsePreSharedKey(presharedKey)  // Pre-shared key from DB
                                .setPersistentKeepalive(persistentKeepalive)  // Persistent keepalive from DB
                                .build()
                        )
                        .build()
                )

                // If successful, mark VPN as connected and update the UI
                dbHandler.addConfiguration("lastVPNUsed", "Wireguard")
                dbHandler.updateConfiguration("lastVPNUsed", "Wireguard")

                // Fetch the acquired IPv4
                val acquiredIpv4 = getVpnIPv4Address()
                if (acquiredIpv4 != null){
                    wireguardVPNIPv4Addr = acquiredIpv4
                }

                // VPN connection established successfully
                activity?.runOnUiThread {
                    updateWireguardActivationSliderStatus(true) // Update UI on the main thread
                    isWireguardConnected = true
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }
    }

    /*Connects the VPN based on the profile UUID*/
    private fun startVPN(profileInline : String) {
        Log.d("OpenVPN", "Attempting to start the OpenVPN profile with UUID: $profileUUID.")

        // Attempt to start the profile and listen for status updates
        try {
            Log.d("OpenVPN","mService: $mService")
            mService?.startVPN(profileInline)

            // Register callback for receiving status updates
            mService?.registerStatusCallback(object : IOpenVPNStatusCallback.Stub() {
                override fun newStatus(uuid: String, state: String, message: String, level: String) {

                    Log.d("OpenVPN", "New Status - UUID: $uuid, State: $state, Message: $message, Level: $level")

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
                        if ("No process running" in message){
                            if (isVpnConnected) {
                                Log.e("OpenVPN", "VPN disconnected or interrupted.")
                                updateActivationSliderStatus(false)
                                isVpnConnected = false
                            }
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

    /*Disconnects the Open VPN*/
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
            activity?.runOnUiThread {
                updateActivationSliderStatus(true)
            }
            isVpnConnected = true
        }
    }

    /*Update the activating slider status*/
    private fun updateActivationSliderStatus(activated: Boolean){
        Log.d("VPN-Handler","Updating VPN resources: $activated")
        if (activated) {
            binding.fragmentVpnIncluded.vpnActiveSlide.isReversed = true
            binding.fragmentVpnIncluded.vpnActiveSlide.outerColor =
                Color.parseColor("#FF0000") //Red
            binding.fragmentVpnIncluded.vpnActiveSlide.setCompleted(completed = false, true)
            binding.fragmentVpnIncluded.vpnActiveSlide.text = "Slide to disconnect"
            binding.fragmentVpnIncluded.vpnStatusTitle.text = "CONNECTED as $myOpenVPNIPv4Addr"
        }
        else{
            binding.fragmentVpnIncluded.vpnActiveSlide.isReversed = false
            binding.fragmentVpnIncluded.vpnActiveSlide.outerColor =
                Color.parseColor("#1F1F1E") //White
            binding.fragmentVpnIncluded.vpnActiveSlide.text = "Slide to active"
            binding.fragmentVpnIncluded.vpnStatusTitle.text = "DISCONNECTED"
            binding.fragmentVpnIncluded.vpnActiveSlide.setCompleted(completed = false, true)
        }
    }

    /*Disconnects to Wireguard Tunnel*/
    private fun disconnectWireguardVPN() {
        println("DISCONNECTING")
        AsyncTask.execute {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                // VPN connection disconnected successfully
                activity?.runOnUiThread {
                    updateWireguardActivationSliderStatus(false) // Update UI on the main thread
                    isWireguardConnected = false
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }
    }

    /*Update the activating slider status*/
    private fun updateWireguardActivationSliderStatus(activated: Boolean){
        Log.d("Wireguard-Handler","Updating VPN resources: $activated")
        if (activated) {
            binding.fragmentVpnIncludedWg.vpnActiveSlide.isReversed = true
            binding.fragmentVpnIncludedWg.vpnActiveSlide.outerColor =
                Color.parseColor("#FF0000") /*Red*/
            binding.fragmentVpnIncludedWg.vpnActiveSlide.setCompleted(completed = false, true)
            binding.fragmentVpnIncludedWg.vpnActiveSlide.text = "Slide to disconnect"
            binding.fragmentVpnIncludedWg.vpnStatusTitle.text = "CONNECTED as $wireguardVPNIPv4Addr"
        }
        else{
            binding.fragmentVpnIncludedWg.vpnActiveSlide.isReversed = false
            binding.fragmentVpnIncludedWg.vpnActiveSlide.outerColor =
                Color.parseColor("#1F1F1E") /*White*/
            binding.fragmentVpnIncludedWg.vpnActiveSlide.text = "Slide to active"
            binding.fragmentVpnIncludedWg.vpnStatusTitle.text = "DISCONNECTED"
            binding.fragmentVpnIncludedWg.vpnActiveSlide.setCompleted(completed = false, true)
        }
    }

    /*Check network status*/
    fun checkNetworkStatus(context: Context): Int {
        // 1. Check if Wi-Fi or mobile data is enabled
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return 1  // Code 1: No Wi-Fi or mobile data enabled
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return 1

        if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return 1  // Code 1: No Wi-Fi or mobile data enabled
        }

        // 2. Check if we have Internet access
        if (!hasInternetAccess()) {
            return 2  // Code 2: Wi-Fi or mobile data enabled, but no Internet access
        }

        // 3. Check if we have IPv6
        if (!checkIfIpv6()) {
            return 3  // Code 3: Wi-Fi or mobile data and Internet access, but no IPv6
        }

        return 0  // Code 0: Wi-Fi or mobile data, Internet access, and IPv6 are all available
    }

    /*Return if has internet access*/
    private fun hasInternetAccess(): Boolean {
        return try {
            val process = ProcessBuilder("ping", "-c", "1", "8.8.8.8").start()
            val returnVal = process.waitFor()
            return returnVal == 0
        } catch (e: Exception) {
            Log.d("Network", "Error while trying to ping: ${e.message}")
            false
        }
    }

    /*Check if we got an Ipv6 from current ISP */
    private fun checkIfIpv6(): Boolean {
        val networkInterfaces = NetworkInterface.getNetworkInterfaces()
        while (networkInterfaces.hasMoreElements()) {
            val networkInterface = networkInterfaces.nextElement()
            val inetAddresses = networkInterface.inetAddresses

            while (inetAddresses.hasMoreElements()) {
                val inetAddress = inetAddresses.nextElement()
                if (inetAddress is InetAddress && inetAddress.hostAddress.contains(":")) {
                    // Found an IPv6 address
                    Log.d("Network","IPv6 address found: ${inetAddress.hostAddress}")
                    return curlIpv6IdentMe()
                }
            }
        }

        // No IPv6 address found
        Log.d("Network","No IPv6 address found.")
        return false
    }

    /*Fetch IPv6 from ident.me*/
    private fun curlIpv6IdentMe(): Boolean {
        return try {
            val process = ProcessBuilder("curl", "-6", "https://ifconfig.me").start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()

            process.waitFor()

            if (output != null && output.contains(":")) {  // Check if the output looks like an IPv6 address
                Log.d("Network","Successfully retrieved IPv6 address: $output")
                true
            } else {
                Log.d("Network","Failed to retrieve IPv6 address. Output: $output")
                false
            }
        } catch (e: Exception) {
            Log.d("Network","Error while trying to curl: ${e.message}")
            false
        }
    }

    /*On activity result coming from OpenVPN For Android*/
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d("OpenVPN Codes","requestCode: $requestCode, resultCode: $resultCode, data: $data")
    };

    /*Handle the VPN activation*/
    private fun handleVPNActivation() {
        /*
        * 0.1 First off, check if the VPN is already active. We might have lost its child process
        *   0.2 If it's off, continue with the standard procedure.
        * 0.3 If it's on, first disconnect it then continue with the following procedure.
        * 1. Check if the DDNS has been set.
        *       1.1. DDNS is set, check the last time the OpenVPN configuration file has been synced.
        *           1.2. The difference time exceeds its timer.
        *           1.3. Resolve the IPv6 address of the FQDN.
        *           1.4. Edit the remote ip address of the last OpenVPN configuration file.
        *           1.5. Delete the current OpenVPN profile and add the new one.
        *           1.6. Connect to OpenVPN.
        *       1.6. The difference time doesn't exceed its time, so connect to OpenVPN without further controls.
        * 2. DDNS is not set, connect to OpenVPN without further controls.
        * */

        binding.fragmentVpnIncluded.vpnStatusTitle.text = "CONNECTING..."

        val ddns = dbHandler.getConfiguration("lastDDNS")
        if (ddns != null && ddns != "" && ddns != "N/D") {
            Log.d("VPN-Handler", "DDNS has been set: $ddns")
            val lastOpenVPNSync = dbHandler.getConfiguration("lastVPNSync")?.toLong()
            Log.d("VPN-Handler", "Last sync is about $lastOpenVPNSync")
            val differenceTime = lastOpenVPNSync?.let { calculateTimeDifferenceInSeconds(it) }
            Log.d("VPN-Handler", "The difference time is: $differenceTime")
            if (differenceTime != null) {
                if (differenceTime > maxExpirationTimer) {
                    Log.d("VPN-Handler", "The time difference exceeds its timer.")
                    val executor = Executors.newSingleThreadExecutor()
                    context?.let { ctx ->
                        getIpAddresses(ctx, ddns, executor) { _, ipv6 ->
                            if (ipv6 != null) {
                                Log.d("VPN-Handler", "Resolved IPv6 of $ddns as: $ipv6 ")
                                val openVPNConfigFile = fetchVPNConfigurationLocally("admin")
                                if (openVPNConfigFile != null) {
                                    Log.d(
                                        "VPN-Handler",
                                        "The OpenVPN configuration file has been successfully fetched."
                                    )
                                    val openVPNEditedFile =
                                        replaceIPv6Address(openVPNConfigFile, ipv6)
                                    Log.d(
                                        "VPN-Handler",
                                        "The OpenVPN configuration file has been updated with the new IPv6: $ipv6."
                                    )
                                    val lastUUID = dbHandler.getConfiguration("lastVPNUUID")
                                    if (lastUUID != null) {
                                        val statusDelete = deleteVPNProfile(lastUUID)
                                        if (statusDelete) {
                                            Log.d(
                                                "VPN-Handler",
                                                "The OpenVPN profile has been deleted"
                                            )
                                            val statusAdding =
                                                addNewOpenvpnProfileLocally(openVPNEditedFile)
                                            if (statusAdding) {
                                                Log.d(
                                                    "VPN-Handler",
                                                    "The new OpenVPN profile has been successfully added, retrieving its UUID."
                                                )
                                                val vpnList = listVPNs()
                                                if (vpnList.isNotEmpty()) {
                                                    val matchingProfile =
                                                        vpnList.find { it.contains("Pi-Starlink") }
                                                    if (matchingProfile != null) {
                                                        profileUUID =
                                                            matchingProfile.split(":").getOrNull(1)
                                                                ?.trim()
                                                        if (profileUUID != null) {
                                                            profileUUID?.let {
                                                                dbHandler.updateConfiguration(
                                                                    "lastUUID",
                                                                    it
                                                                )
                                                                Log.d(
                                                                    "VPN-Handler",
                                                                    "Updating the last VPN sync."
                                                                )
                                                                val currentTimeStamp =
                                                                    System.currentTimeMillis()
                                                                        .toString()
                                                                dbHandler.updateConfiguration(
                                                                    "lastVPNSync",
                                                                    currentTimeStamp
                                                                )
                                                                Log.d(
                                                                    "VPN-Handler",
                                                                    "All done! Connecting to VPN with UUID: $profileUUID."
                                                                )

                                                                /*Avoid OpenVPN For Android bug, check if is the first time*/
                                                                val isFirstVPN =
                                                                    dbHandler.getConfiguration("isFirstVPN")
                                                                Log.d(
                                                                    "VPN-Handler",
                                                                    "isFirstVPN: $isFirstVPN"
                                                                )
                                                                if (isFirstVPN != null) {
                                                                    if (isFirstVPN == "0") {
                                                                        connectVPN(profileUUID!!)
                                                                    } else if (isFirstVPN == "1") {
                                                                        Log.d(
                                                                            "VPN-Handler",
                                                                            "This is the first VPN activation ever."
                                                                        )
                                                                        //startOpenVPNProfile(context!!,"Pi-Starlink")
                                                                        dbHandler.updateConfiguration(
                                                                            "isFirstVPN",
                                                                            "0"
                                                                        )
                                                                        connectVPN(profileUUID!!)
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            Log.e(
                                                                "VPN-Handler",
                                                                "instance of profileUUID is null."
                                                            )
                                                        }
                                                    } else {
                                                        Log.e(
                                                            "VPN-Handler",
                                                            "No one of the profiles contain 'Pi-Starlink'"
                                                        )
                                                    }
                                                } else {
                                                    Log.e("VPN-Handler", "The vpn list is empty.")
                                                }
                                            } else {
                                                Log.e(
                                                    "VPN-Handler",
                                                    "An error occurred while adding the new OpenVPN Profile."
                                                )
                                            }
                                        } else {
                                            Log.e(
                                                "VPN-Handler",
                                                "An error occurred while deleting the OpenVPN Profile."
                                            )
                                        }
                                    } else {
                                        Log.e(
                                            "VPN-Handler",
                                            "lastUUID is null. Skipping renewing process and I'll try to connect the VPN anyways."
                                        )
                                    }
                                }
                            } else {
                                Log.e(
                                    "VPN-Handler",
                                    "IPv6 address not found. Skipping renewing process and I'll try to connect the VPN anyways."
                                )
                            }
                        }
                    }
                } else {
                    Log.d(
                        "VPN-Handler",
                        "The time difference doesn't exceed its timer. Connecting to VPN."
                    )
                    val lastUUID = dbHandler.getConfiguration("lastVPNUUID")
                    if (lastUUID != null) {
                        val isFirstVPN = dbHandler.getConfiguration("isFirstVPN")
                        Log.d("VPN-Handler", "isFirstVPN: $isFirstVPN")
                        Log.d("VPN-Handler", "This is the first VPN activation ever.")
                        if (isFirstVPN != null) {
                            if (isFirstVPN == "0") {
                                connectVPN(profileUUID!!)
                            } else if (isFirstVPN == "1") {
                                //startOpenVPNProfile(context!!,"Pi-Starlink")
                                dbHandler.updateConfiguration("isFirstVPN", "0")
                                connectVPN(profileUUID!!)
                            }
                        }
                    }
                }
            }
            else{
                Log.d("VPN-Handler", "No DDNS has been set up, proceeding without.")
                val executor = Executors.newSingleThreadExecutor()
                context?.let {
                    val vpnList = listVPNs()
                    if (vpnList.isNotEmpty()) {
                        val matchingProfile = vpnList.find { it.contains("Pi-Starlink") }
                        if (matchingProfile != null) {
                            profileUUID = matchingProfile.split(":").getOrNull(1)?.trim()
                            if (profileUUID != null) {
                                profileUUID?.let {
                                    dbHandler.updateConfiguration(
                                        "lastUUID",
                                        it
                                    )
                                    Log.d("VPN-Handler", "Updating the last VPN sync.")
                                    val currentTimeStamp = System.currentTimeMillis().toString()
                                    dbHandler.updateConfiguration("lastVPNSync", currentTimeStamp)
                                    Log.d(
                                        "VPN-Handler",
                                        "All done! Connecting to VPN with UUID: $profileUUID."
                                    )

                                    /*Avoid OpenVPN For Android bug, check if is the first time*/
                                    val isFirstVPN = dbHandler.getConfiguration("isFirstVPN")
                                    Log.d("VPN-Handler", "isFirstVPN: $isFirstVPN")
                                    if (isFirstVPN != null) {
                                        if (isFirstVPN == "0") {
                                            connectVPN(profileUUID!!)
                                        } else if (isFirstVPN == "1") {
                                            Log.d(
                                                "VPN-Handler",
                                                "This is the first VPN activation ever."
                                            )
                                            //startOpenVPNProfile(context!!,"Pi-Starlink")
                                            dbHandler.updateConfiguration("isFirstVPN", "0")
                                            connectVPN(profileUUID!!)
                                        }
                                    }
                                }
                            } else {
                                Log.e("VPN-Handler", "instance of profileUUID is null.")
                            }
                        } else {
                            Log.e("VPN-Handler", "No one of the profiles contain 'Pi-Starlink'")
                        }
                    } else {
                        Log.e("VPN-Handler", "The vpn list is empty.")
                    }
                }
            }
        } else {
            Log.d("VPN-Handler", "No DDNS has been set up, proceeding without.")
            val executor = Executors.newSingleThreadExecutor()
            context?.let {
                val vpnList = listVPNs()
                if (vpnList.isNotEmpty()) {
                    val matchingProfile = vpnList.find { it.contains("Pi-Starlink") }
                    if (matchingProfile != null) {
                        profileUUID = matchingProfile.split(":").getOrNull(1)?.trim()
                        if (profileUUID != null) {
                            profileUUID?.let {
                                dbHandler.updateConfiguration(
                                    "lastUUID",
                                    it
                                )
                                Log.d("VPN-Handler", "Updating the last VPN sync.")
                                val currentTimeStamp = System.currentTimeMillis().toString()
                                dbHandler.updateConfiguration("lastVPNSync", currentTimeStamp)
                                Log.d(
                                    "VPN-Handler",
                                    "All done! Connecting to VPN with UUID: $profileUUID."
                                )

                                /*Avoid OpenVPN For Android bug, check if is the first time*/
                                val isFirstVPN = dbHandler.getConfiguration("isFirstVPN")
                                Log.d("VPN-Handler", "isFirstVPN: $isFirstVPN")
                                if (isFirstVPN != null) {
                                    if (isFirstVPN == "0") {
                                        connectVPN(profileUUID!!)
                                    } else if (isFirstVPN == "1") {
                                        Log.d(
                                            "VPN-Handler",
                                            "This is the first VPN activation ever."
                                        )
                                        //startOpenVPNProfile(context!!,"Pi-Starlink")
                                        dbHandler.updateConfiguration("isFirstVPN", "0")
                                        connectVPN(profileUUID!!)
                                    }
                                }
                            }
                        } else {
                            Log.e("VPN-Handler", "instance of profileUUID is null.")
                        }
                    } else {
                        Log.e("VPN-Handler", "No one of the profiles contain 'Pi-Starlink'")
                    }
                } else {
                    Log.e("VPN-Handler", "The vpn list is empty.")
                }
            }
        }
    }

    /*Handle the VPN activation*/
    private fun handleWireguardVPNActivation() {
        /*
        * 0.1 First of all, check if the VPN is already active. We might have lost its child process
        *   0.2 If it's off, continue with the standard procedure.
        * 0.3 If it's on, first disconnect it then continue with the following procedure.
        * 1. Check if the DDNS has been set.
        *       1.1. DDNS is set, check the last time Wireguard configuration file has been synced.
        *           1.2. The difference time exceeds its timer.
        *           1.3. Resolve the IPv6 address of the FQDN.
        *           1.4. Edit the remote IP address of the last Wireguard configuration file.
        *           1.6. Connect to Wireguard.
        *       1.6. The difference time doesn't exceed its time, so connect to Wireguard without further controls.
        * 2. DDNS is not set, connect to Wireguard without further controls.
        * */

        binding.fragmentVpnIncludedWg.vpnStatusTitle.text = "CONNECTING..."

        /*Checking DDNS*/
        val ddns = dbHandler.getConfiguration("lastDDNS")

        /*DDNS is not null*/
        if (ddns != null && ddns != "" && ddns != "N/D") {
            Log.d("Wireguard-Handler", "DDNS has been set: $ddns")

            /*Updating last sync*/
            val lastWireguardVPNSync = dbHandler.getConfiguration("lastWireguardSync")?.toLong()
            Log.d("Wireguard-Handler", "Last sync is about $lastWireguardVPNSync")

            /*Calculating the difference time*/
            val differenceTime = lastWireguardVPNSync?.let { calculateTimeDifferenceInSeconds(it) }
            Log.d("Wireguard-Handler", "The difference time is: $differenceTime")

            /*Difference time is not null*/
            if (differenceTime != null) {

                /*Difference time exceeds max limit*/
                if (differenceTime > maxExpirationTimer) {
                    Log.d("Wireguard-Handler", "The time difference exceeds its timer.")

                    /*Initialize executors*/
                    val executor = Executors.newSingleThreadExecutor()
                    context?.let { ctx ->

                        /*Fetch IPv6*/
                        Log.d("Wireguard-Handler","Fetching IPv6 of DDNS: $ddns")
                        getIpAddresses(ctx, ddns, executor) { _, ipv6 ->

                            /*IPv6 is not null*/
                            if (ipv6 != null) {
                                Log.d("Wireguard-Handler", "Resolved IPv6 of $ddns as: $ipv6 ")

                                /*Add or update Endpoint on DB*/
                                dbHandler.addConfiguration("Endpoint","[$ipv6]:51820")
                                dbHandler.updateConfiguration("Endpoint","[$ipv6]:51820")

                                /*Fetch vpn list*/
                                val vpnList = listWireguardVPNs()

                                /*Vpn list is not empty*/
                                if (vpnList.isNotEmpty()) {

                                    /*Updating*/
                                    val currentTimeStamp =
                                        System.currentTimeMillis()
                                            .toString()
                                    dbHandler.updateConfiguration(
                                        "lastWireguardSync",
                                        currentTimeStamp
                                    )

                                    /*Connect to Wireguard*/
                                    connectWireguard()

                                } else {
                                    Log.e("Wireguard-Handler", "The vpn list is empty.")
                                }
                            } else {
                                Log.e(
                                    "Wireguard-Handler",
                                    "IPv6 address not found. Skipping renewing process and I'll try to connect the VPN anyways."
                                )
                            }
                        }
                    }
                } else {
                    Log.d(
                        "Wireguard-Handler",
                        "The time difference doesn't exceed its timer. Connecting to VPN."
                    )
                    /*Connect to Wireguard tunnel*/
                    connectWireguard()
                }
            }
            else{
                Log.d("Wireguard-Handler", "No DDNS has been set up, proceeding without.")
                context?.let {

                    /*Fetch vpn list*/
                    val vpnList = listWireguardVPNs()

                    /*Vpn list is not empty*/
                    if (vpnList.isNotEmpty()) {
                        run {
                            Log.d("Wireguard-Handler", "Updating the last VPN sync.")
                            val currentTimeStamp = System.currentTimeMillis().toString()

                            /*Add and update lastWireguardSync to DB*/
                            dbHandler.updateConfiguration("lastWireguardSync", currentTimeStamp)
                            dbHandler.addConfiguration("lastWireguardSync", currentTimeStamp)
                            Log.d(
                                "Wireguard-Handler",
                                "All done! Connecting to Wireguard."
                            )

                            /*Connect to Wireguard tunnel*/
                            connectWireguard()
                        }
                    } else {
                        Log.e("VPN-Handler", "The vpn list is empty.")
                    }
                }
            }
        } else {
            Log.d("Wireguard-Handler", "No DDNS has been set up, proceeding without.")
            context?.let {

                /*Fetch vpn list*/
                val vpnList = listWireguardVPNs()

                /*Vpn list is not empty*/
                if (vpnList.isNotEmpty()) {
                    run {
                        Log.d("Wireguard-Handler", "Updating the last VPN sync.")
                        val currentTimeStamp = System.currentTimeMillis().toString()

                        /*Add and update lastWireguardSync on DB*/
                        dbHandler.addConfiguration("lastWireguardSync", currentTimeStamp)
                        dbHandler.updateConfiguration("lastWireguardSync", currentTimeStamp)
                        Log.d(
                            "Wireguard-Handler",
                            "All done! Connecting to Wireguard."
                        )

                        /*Connect to Wireguard Tunnel*/
                        connectWireguard()
                    }
                } else {
                    Log.e("Wireguard-Handler", "The vpn list is empty.")
                }
            }
        }
    }

    /*Calculate the difference time and print it*/
    private fun calculateTimeDifferenceInSeconds(lastVPNSyncTimestamp: Long): Long {
        val currentTimestamp = System.currentTimeMillis()
        val differenceInMillis = currentTimestamp - lastVPNSyncTimestamp
        val differenceInSeconds = differenceInMillis / 1000
        Log.d("VPN-Handler","Time difference in seconds: $differenceInSeconds")
        return differenceInSeconds
    }

    /*Prepare database with configurations*/
    private fun prepareDBConfig(){
        /*User status*/
        dbHandler.addConfiguration("first_boot","1")

        /*Luci Webpage*/
        dbHandler.addConfiguration("luci_username","root")
        dbHandler.addConfiguration("luci_password","")

        /*DDNS*/
        dbHandler.addConfiguration("is_ddns_set","0")

        /*VPN*/
        dbHandler.addConfiguration("isFirstVPN","1")
    }

    /*Check if a given app is installed*/
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            requireContext().packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /*Start the OpenVPN Profile through the 'am' command*/
    private fun startOpenVPNProfile(context: Context, profileName: String) {
        // Create an Intent with the action to start the OpenVPN profile
        val intent = Intent("android.intent.action.MAIN").apply {
            component = ComponentName("de.blinkt.openvpn", "de.blinkt.openvpn.api.ConnectVPN")
            putExtra("de.blinkt.openvpn.api.profileName", profileName)
        }

        // Check if the package is installed
        val packageManager = context.packageManager
        if (intent.resolveActivity(packageManager) != null) {
            // Start the activity
            context.startActivity(intent)
            Log.d("OpenVPN", "Starting OpenVPN profile: $profileName")
        } else {
            Log.e("OpenVPN", "OpenVPN package not installed.")
        }
    }

    /*Check if the vpn is active*/
    private fun isVpnActive(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            /*For Android Marshmallow and above*/
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } else {

            /*For older versions of Android*/
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            activeNetworkInfo != null && activeNetworkInfo.type == ConnectivityManager.TYPE_VPN
        }
    }

    /*Fetch IPv4 and IPv6 addresses*/
    private fun getIpAddresses(
        context: Context,
        fqdn: String,
        executor: Executor,
        callback: (ipv4: String?, ipv6: String?) -> Unit
    ) {
        val dnsResolver = DnsResolver.getInstance()
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network: Network? = connectivityManager.activeNetwork
        val cancellationSignal = CancellationSignal()

        var ipv4Address: String? = null
        var ipv6Address: String? = null

        // Define the callback for IPv4 and IPv6 resolution
        val dnsCallback = object : DnsResolver.Callback<MutableList<InetAddress>> {
            override fun onAnswer(answer: MutableList<InetAddress>, rcode: Int) {
                for (inetAddress in answer) {
                    if (inetAddress.address.size == 4) {  // IPv4
                        ipv4Address = inetAddress.hostAddress
                    } else if (inetAddress.address.size == 16) {  // IPv6
                        ipv6Address = inetAddress.hostAddress
                    }
                }
                // Invoke the callback after both addresses are resolved
                callback(ipv4Address, ipv6Address)
            }

            override fun onError(error: DnsResolver.DnsException) {
                Log.e("DnsResolver", "DNS resolution failed: ${error.message}")
                callback(null, null)
            }
        }

        // Issue the DNS query for both A (IPv4) and AAAA (IPv6) records
        dnsResolver.query(
            network,
            fqdn,
            DnsResolver.FLAG_EMPTY,  // No specific flags
            executor,
            cancellationSignal,
            dnsCallback
        )
    }

    /*Closes all the non-home fragments*/
    private fun closeFragment(){
        if (binding.homeMainLayout.visibility != View.VISIBLE) {
            binding.fragmentVpnIncluded.root.visibility = View.GONE
            binding.fragmentVpnIncludedWg.root.visibility = View.GONE
            binding.fragmentDdnsIncluded.root.visibility = View.GONE
            binding.fragmentSettingsIncluded.root.visibility = View.GONE
            binding.fragmentNetworkIncluded.root.visibility = View.GONE
            binding.fragmentPortForwardingIncluded.root.visibility = View.GONE
            binding.fragmentSupportIncluded.root.visibility = View.GONE
            binding.fragmentSystemIncluded.root.visibility = View.GONE
            binding.homeMainLayout.visibility = View.VISIBLE
        }
        else{
            requireActivity().moveTaskToBack(true)
        }
    }

    override fun onVpnStatusUpdated(status: Boolean) {
        println("GOT IT!")
        // This method will be called from MyVpnService
        activity?.runOnUiThread {
            updateWireguardActivationSliderStatus(true) // Update UI on the main thread
            isWireguardConnected = true
        }
    }

}

/*
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

/*
 * Class to handle local files
 */
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

    fun readFileFromInternalStorage(fileName: String): String {
        return try {
            context.openFileInput(fileName).bufferedReader().useLines { lines ->
                lines.joinToString("\n")
            }
        } catch (e: Exception) {
            Log.e("FileHelper", "Error reading file: ${e.message}")
            ""
        }
    }
}

/*
 * Javascript interface *
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

/* Class for Wireguard Tunnel*/
class WgTunnel : Tunnel {
    override fun getName(): String {
        return "wgpreconf"
    }
    override fun onStateChange(newState: Tunnel.State) {
        Log.d("Wireguard-State", "A new state has been recorded: $newState")
    }
}

interface MyVpnServiceCallback {
    fun onVpnStatusUpdated(status: Boolean)
}