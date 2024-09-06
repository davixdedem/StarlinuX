<!-- Improved compatibility of back to top link: See: https://github.com/othneildrew/Best-README-Template/pull/73 -->
<a id="readme-top"></a>
<!--
*** Thanks for checking out the Best-README-Template. If you have a suggestion
*** that would make this better, please fork the repo and create a pull request
*** or simply open an issue with the tag "enhancement".
*** Don't forget to give the project a star!
*** Thanks again! Now go create something AMAZING! :D
-->



<!-- PROJECT SHIELDS -->
<!--
*** I'm using markdown "reference style" links for readability.
*** Reference links are enclosed in brackets [ ] instead of parentheses ( ).
*** See the bottom of this document for the declaration of the reference variables
*** for contributors-url, forks-url, etc. This is an optional, concise syntax you may use.
*** https://www.markdownguide.org/basic-syntax/#reference-style-links
-->
[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![MIT License][license-shield]][license-url]
[![LinkedIn][linkedin-shield]][linkedin-url]



<!-- PROJECT LOGO -->
<br />
<div align="center">
  <a href="https://github.com/github_username/repo_name">
    <img src="images/logo2.png" alt="Logo" width="500" height="60">
  </a>

<h3 align="center">The Rasberry Pi's unofficial solution for Starlink.</h3>

  <p align="center">
    <a href="https://github.com/github_username/repo_name"><strong>Explore the docs »</strong></a>
    <br />
    <br />
    <a href="https://github.com/github_username/repo_name">View Demo</a>
    ·
    <a href="https://github.com/github_username/repo_name/issues/new?labels=bug&template=bug-report---.md">Report Bug</a>
    ·
    <a href="https://github.com/github_username/repo_name/issues/new?labels=enhancement&template=feature-request---.md">Request Feature</a>
  </p>
</div>


<!-- TABLE OF CONTENTS -->
<details>
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
      <ul>
        <li><a href="#built-with">Built With</a></li>
      </ul>
    </li>
    <li>
      <a href="#getting-started">Getting Started</a>
      <ul>
        <li><a href="#prerequisites">Prerequisites</a></li>
        <li><a href="#installation">Installation</a></li>
      </ul>
    </li>
    <li><a href="#usage">Usage</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
    <li><a href="#contact">Contact</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details>


<!-- ABOUT THE PROJECT -->
## What's Pi Starlink?


<div align="center">
  <a href="https://github.com/github_username/repo_name">
    <img src="images/round_logo.png" alt="Logo" width="300" height="150">
  </a>
</div>
<p align="center">
<img src="https://camo.githubusercontent.com/1ddf90e524a4bfe8b77f9a6902d54fc708380389b7e0d7f9ad29196a799e77db/68747470733a2f2f706c61792e676f6f676c652e636f6d2f696e746c2f656e5f75732f6261646765732f696d616765732f67656e657269632f656e2d706c61792d62616467652e706e67" width="25%">
<img src="https://camo.githubusercontent.com/0014f07b7f169b7232d26d242bb2f8ef598dea7169bd8766385d70d6be8127a1/68747470733a2f2f662d64726f69642e6f72672f62616467652f6765742d69742d6f6e2e706e67" width="25%">
</p>  



Since Starlink provides their clients a public <i>IPv6</i>, which is globally accessible, we leveraged on it building from scratch a custom image of <b>OpenWRT</b> dedicated to Raspberry Pi. 
This offers a proper and reliable Modem/Router with pre-configured settings studiously assessed for working with the Starlink Dish as plug & play. <br>

<div align="center">
  <i><b>Get now our Android app...</b></i>
</div>
<br>

The mobile application of <b>Pi Starlink</b> will allow you to set up your own free VPN all over IPv6 in a few easy steps and reach your home network while ensuring adequate security you need to surf the net safely. 
The VPN is based on <i>OpenVPN</i>, which is free and also, it works with the APIs provided by the fork project of <a href="https://github.com/schwabe/ics-openvpn"><i>OpenVPN for Android</i></a>. Pi Starlink is not only oriented to the normal Starlink customers but even to people who want 
to host their own public server globally as our application has specific features for managing <b>port forwarding</b> rules. In case you get an ethernet port extender, we’ll be able to set up a proper <b>DMZ</b>, all is up to you!<br>

<div align="center">
  <i><b>...IPv6, what a Saviour!</b></i>
</div>
<br>

An IPv6 address is certainly not easy to remember because of its length, that’s why our application points you in a good direction for you in order to set up a free Dynamic DNS, getting a fully qualified domain name to share with
people to you want to reach your game server, or your web page! We tried to raise an application that is far from complex, but feel free to open an issue if you encounter any problem.<br>

<div align="center">
  <i><b>Just in case you get a Power Over Ethernet!</b></i>
</div>
<br>
<div align="center">
  <a href="https://github.com/github_username/repo_name">
    <img src="images/poe.png" alt="Logo" width="500" height="300">
  </a>
</div>

One of the main goals of this project is power saving, because Pi Starlink replaced the original Starlink router, which has a higher power consumption compared to the low cost <b>Single Board Computer</b> we’re working on. 
As a matter of fact, most of the Starlink vanlifer users might be interested in this project as the power is a critical point they used to facing every day when they travel, and that’s now something very reachable. 
Just buy the PoE(Power over Ethernet) which powers up all your items from 12v switching to 46v avoiding any A.C inverter.<br>

<p align="right">(<a href="#readme-top">back to top</a>)</p>


<!-- GETTING STARTED -->
## Getting Started

This is an example of how you may give instructions on setting up your project locally.
To get a local copy up and running follow these simple example steps.

### Prerequisites

This is an example of how to list things you need to use the software and how to install them.
* npm
  ```sh
  npm install npm@latest -g
  ```

### Installation

1. Get a free API Key at [https://example.com](https://example.com)
2. Clone the repo
   ```sh
   git clone https://github.com/github_username/repo_name.git
   ```
3. Install NPM packages
   ```sh
   npm install
   ```
4. Enter your API in `config.js`
   ```js
   const API_KEY = 'ENTER YOUR API';
   ```
5. Change git remote url to avoid accidental pushes to base project
   ```sh
   git remote set-url origin github_username/repo_name
   git remote -v # confirm the changes
   ```

<p align="right">(<a href="#readme-top">back to top</a>)</p>


<!-- USAGE -->
## Usage

Use this space to show useful examples of how a project can be used. Additional screenshots, code examples and demos work well in this space. You may also link to more resources.

_For more examples, please refer to the [Documentation](https://example.com)_

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- VPN setting -->
## Set up your own VPN
<div align="center">
    <img src="images/vpn_plus_pi.png" alt="Logo" width="220" height="90">
</div>
1. First of all, be sure you've already installed <a href="https://github.com/schwabe/ics-openvpn"><i>OpenVPN for Android</i></a> on your Smartphone.<br>
2. In Pi Starlink, go to the <i><b>Virtual Private Network(VPN)</b></i> section.<br>
3. Slide the configuration button, Pi Starlink will take a few minutes to configure OpenVPN depending on your Raspberry Pi efficiency.<br>
4. Once is done, turn off your Wi-Fi connection and use only your <b>Mobile Network</b>.
5. Slide the activation button, your smartphone will be assigned a <b>local IPv4 address</b>. <br>
6. Congrats, you're a virtually home! <br>
7. For disconnecting, slide it back.<br>

### Some tips to check if everything works:
- <b>From your Smartphone:</b>
1. Once your connected to the VPN,try to open your router page <b>http://192.168.1.1</b> from your Smartphone.
2. If you reach it, it means everything is working!
   
- <b>From an external device:</b>
1. Once your Smartphone is connected to the VPN, check its assigned local IPv4 into the <i>Virtual Private Networn(VPN)</i> section, in this case is <b>192.168.9.2</b>.
2. Use a device connected on the local network to ping your Smartphone.
```sh
ping 192.168.9.2
```
3. You should see the following output.
```sh
PING 192.168.9.2 (192.168.9.2) 56(84) bytes of data.
64 bytes from 192.168.9.2: icmp_seq=1 ttl=63 time=128 ms
64 bytes from 192.168.9.2: icmp_seq=2 ttl=63 time=114 ms
64 bytes from 192.168.9.2: icmp_seq=3 ttl=63 time=138 ms
64 bytes from 192.168.9.2: icmp_seq=4 ttl=63 time=109 ms
```
### For any further data:
SSH into your Pi Starlink and check the <i>OpenVPN</i> logs:
```sh
tail -f /var/log/openvpn.log
```
```sh
tail -f /var/log/openvpn_status.log
```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- DDNS setting -->
## Set up your own DDNS
<div align="center">
    <img src="images/mynoip.png" alt="Logo" width="80" height="80">
</div>
1. First of all, register and sign up at <a href="https://my.noip.com">https://my.noip.com</a><br>
2. Create a new hostname through the control panel.(e.g <i>my-starlink-home.ddns.net</i>) <br>
3. Setup a new DDNS key, you'll receive <b>username</b> and <b>password</b><br>
4. In Pi Starlink, go to the <i><b>Dynamic DNS(DDNS)</b></i> section.<br>
5. Fill out the form with the <b>hostname</b> and your <b>DDNS key credentials</b>.
6. Congrats, you've set up your DDNS!
7. Pi Starlink will take care to sync your FQDN with the current IPv6 on time.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- FAQ -->
## FAQ
<details> <summary><b>I'm currently running the basic plan of Starlink, is it possible to set up my own VPN anyway?</b></summary>
Certainly! You do have the possibility to set up your own VPN even with the basic Starlink plan.

</details> <details> <summary><b>My Smartphone's mobile network doesn't provide an IPv6, how can I reach my Starlink network?</b></summary>
Sorry, but an assigned IPv6 is mandatory. We suggest you to find a new ISP in order to obtain an IPv6 address.

</details> <details> <summary><b>How can I check if my Smartphone is running an IPv6?</b></summary>
Try connecting to the VPN. If you're missing IPv6, an alert will advise you. Alternatively, you can visit <a href="https://test-ipv6.com/">checkipv6.com</a> to test your ISP.

</details> <details> <summary><b>My Minecraft server is listening on port 5555, how do I make people reach my server?</b></summary>
The easiest way is to set up Port Forwarding. Go to the dedicated section in the app.

</details> <details> <summary><b>Does this application substitute the original Starlink app?</b></summary>
Of course not, Pi Starlink handles the Router while Starlink manages the Dish.

</details> <details> <summary><b>Do I need to install OpenVPN for Android?</b></summary>
Yes, you do. But don't worry, you'll manage everything from Pi Starlink.

</details>

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- ROADMAP -->
## Roadmap

- [ ] Feature 1
- [ ] Feature 2
- [ ] Feature 3
    - [ ] Nested Feature

See the [open issues](https://github.com/github_username/repo_name/issues) for a full list of proposed features (and known issues).

<p align="right">(<a href="#readme-top">back to top</a>)</p>



<!-- CONTRIBUTING -->
## Contributing

Contributions are what make the open source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.

If you have a suggestion that would make this better, please fork the repo and create a pull request. You can also simply open an issue with the tag "enhancement".
Don't forget to give the project a star! Thanks again!

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Top contributors:

<a href="https://github.com/github_username/repo_name/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=github_username/repo_name" alt="contrib.rocks image" />
</a>



<!-- LICENSE -->
## License

Distributed under the MIT License. See `LICENSE.txt` for more information.

<p align="right">(<a href="#readme-top">back to top</a>)</p>



<!-- CONTACT -->
## Contact

Your Name - [@twitter_handle](https://twitter.com/twitter_handle) - email@email_client.com

Project Link: [https://github.com/github_username/repo_name](https://github.com/github_username/repo_name)

<p align="right">(<a href="#readme-top">back to top</a>)</p>



<!-- ACKNOWLEDGMENTS -->
## Acknowledgments

* []()
* []()
* []()

<p align="right">(<a href="#readme-top">back to top</a>)</p>



<!-- MARKDOWN LINKS & IMAGES -->
<!-- https://www.markdownguide.org/basic-syntax/#reference-style-links -->
[contributors-shield]: https://img.shields.io/github/contributors/github_username/repo_name.svg?style=for-the-badge
[contributors-url]: https://github.com/github_username/repo_name/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/github_username/repo_name.svg?style=for-the-badge
[forks-url]: https://github.com/github_username/repo_name/network/members
[stars-shield]: https://img.shields.io/github/stars/github_username/repo_name.svg?style=for-the-badge
[stars-url]: https://github.com/github_username/repo_name/stargazers
[issues-shield]: https://img.shields.io/github/issues/github_username/repo_name.svg?style=for-the-badge
[issues-url]: https://github.com/github_username/repo_name/issues
[license-shield]: https://img.shields.io/github/license/github_username/repo_name.svg?style=for-the-badge
[license-url]: https://github.com/github_username/repo_name/blob/master/LICENSE.txt
[linkedin-shield]: https://img.shields.io/badge/-LinkedIn-black.svg?style=for-the-badge&logo=linkedin&colorB=555
[linkedin-url]: https://linkedin.com/in/linkedin_username
[product-screenshot]: images/screenshot.png
[Next.js]: https://img.shields.io/badge/next.js-000000?style=for-the-badge&logo=nextdotjs&logoColor=white
[Next-url]: https://nextjs.org/
[React.js]: https://img.shields.io/badge/React-20232A?style=for-the-badge&logo=react&logoColor=61DAFB
[React-url]: https://reactjs.org/
[Vue.js]: https://img.shields.io/badge/Vue.js-35495E?style=for-the-badge&logo=vuedotjs&logoColor=4FC08D
[Vue-url]: https://vuejs.org/
[Angular.io]: https://img.shields.io/badge/Angular-DD0031?style=for-the-badge&logo=angular&logoColor=white
[Angular-url]: https://angular.io/
[Svelte.dev]: https://img.shields.io/badge/Svelte-4A4A55?style=for-the-badge&logo=svelte&logoColor=FF3E00
[Svelte-url]: https://svelte.dev/
[Laravel.com]: https://img.shields.io/badge/Laravel-FF2D20?style=for-the-badge&logo=laravel&logoColor=white
[Laravel-url]: https://laravel.com
[Bootstrap.com]: https://img.shields.io/badge/Bootstrap-563D7C?style=for-the-badge&logo=bootstrap&logoColor=white
[Bootstrap-url]: https://getbootstrap.com
[JQuery.com]: https://img.shields.io/badge/jQuery-0769AD?style=for-the-badge&logo=jquery&logoColor=white
[JQuery-url]: https://jquery.com 
