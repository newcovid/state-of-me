# Third-party components

State of Me desktop source is MIT licensed. This distribution also includes:

- WPF UI 4.3.0: MIT, https://github.com/lepoco/wpfui . License and upstream third-party notices are in licenses/.
- NAudio 2.2.1: MIT, https://github.com/naudio/NAudio . License in licenses/.
- TagLibSharp 2.3.0: LGPL-2.1-only, copyright Brian Nickel, Gabriel Burt, Stephen Shaw and contributors. https://github.com/mono/taglib-sharp . Unmodified dynamically linked TagLibSharp.dll; its LGPL text and complete corresponding source archive (tag TaglibSharp-2.3.0.0, commit b5ae84f2e84087bf160bb0471420200dd2b5d809) are supplied with this release: LGPL text in licenses/, complete source in the separate TagLibSharp-2.3.0-source.tar.gz release asset.
- .NET Windows Desktop runtime and Microsoft libraries: corresponding runtime LICENSE.txt / ThirdPartyNotices.txt distributed with the self-contained runtime; https://github.com/dotnet .

You may replace TagLibSharp.dll with a compatible modified build. No restriction is imposed on reverse engineering required to debug modifications to that LGPL library. This application does not modify TagLibSharp or statically merge it into the executable. The archive includes its build instructions and source; application source and reproducible project files are available at https://github.com/newcovid/state-of-me .
