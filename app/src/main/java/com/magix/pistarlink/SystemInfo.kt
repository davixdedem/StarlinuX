package com.magix.pistarlink

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.magix.pistarlink.databinding.FragmentSystemInfoBinding

class SystemInfo : Fragment() {

    private var _binding: FragmentSystemInfoBinding? = null
    private val binding get() = _binding!!
    private lateinit var openWRTApi: OpenWRTApi

    /*Luci configurations*/
    private val username = "root"
    private val password = "t*iP9Tk6na3VPeq"
    private val baseUrl = "http://192.168.1.1"
    private val openWRTTag = "OpenWRT"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSystemInfoBinding.inflate(inflater, container, false)
        val root: View = binding.root

        /*Init*/
        openWRTApi = OpenWRTApi(baseUrl,username,password)

        return  root
    }

}