# LocalDrop kurulumu

**LocalDrop 0.1.0 geliştirme aşamasındadır.** Windows için tek tıklamalı kurulum EXE’si üretilebiliyor. İmzasız önizleme dosyası, [GitHub Actions](https://github.com/bevyss/LocalDrop/actions/workflows/ci.yml) içindeki başarılı son **CI** çalışmasının **Artifacts** bölümünde bulunur. `LocalDrop-Setup-0.1.0-win-x64-unsigned` ZIP dosyasını indirip açın, içindeki `LocalDrop-Setup-0.1.0-win-x64.exe` dosyasını çalıştırın. Kurulum yönetici yetkisi istemez; Başlat menüsüne LocalDrop kısayolu ekler ve Windows'un **Yüklü uygulamalar** bölümünden kaldırılabilir. Bu önizleme dijital olarak imzalı değildir; Windows güvenlik uyarısı gösterebilir. İmzalı, doğrulanmış bir sürüm henüz [Releases](https://github.com/bevyss/LocalDrop/releases) sayfasında yok. Android sürüm APK’sı da henüz yayımlanmadı. GitHub’ın **Source code** ZIP dosyası kurulum paketi değildir. Ayrıntılı İngilizce rehber: [INSTALL.md](INSTALL.md).

## Gereksinimler

- Windows 10/11 veya Android 8.0 ve üzeri
- Derleme için [.NET 10 SDK](https://dotnet.microsoft.com/download/dotnet/10.0), [WinUI geliştirme araçları](https://learn.microsoft.com/windows/apps/get-started/start-here), JDK 17 ve Android SDK Platform 36
- Aktarım sırasında iki cihazın aynı yerel ağda olması ve Android uygulamasının açık kalması

## Windows’ta çalıştırma

[Depodan](https://github.com/bevyss/LocalDrop) **Code → Download ZIP** ile kaynak kodu indirip açın. Açılan klasörde PowerShell çalıştırın:

```powershell
dotnet build apps/windows/LocalDrop.Windows/LocalDrop.Windows.csproj -p:Platform=x64
dotnet run --project apps/windows/LocalDrop.Windows/LocalDrop.Windows.csproj -p:Platform=x64
```

Bu komut geliştirme paketini açar; kalıcı bir son kullanıcı kurulum paketi oluşturmaz. Güvenlik duvarı sorarsa yalnızca **özel ağlar** için izin verin.

Kendi kurulum EXE’nizi üretmek için [Inno Setup 6](https://jrsoftware.org/isdl.php) kurduktan sonra depo kökünde `./scripts/build-windows-installer.ps1` çalıştırın. Çıktı `.tools/release/LocalDrop-Setup-0.1.0-win-x64.exe` konumunda oluşur. Derleme için .NET 10 SDK ve WinUI araçları gerekir; kurulum dosyasını kullananların bunları kurması gerekmez.

## Android’de çalıştırma

`ANDROID_HOME` değişkenini Android SDK klasörüne ayarlayın. Depo kökünde:

```powershell
cd apps/android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

Üretilen debug APK `apps/android/app/build/outputs/apk/debug/app-debug.apk` konumundadır. USB hata ayıklaması açık bir cihaza kurmak için `apps/android` klasöründe şu komutu çalıştırın:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug APK geliştirme anahtarıyla imzalanır; herkese dağıtılacak sürüm için sabit bir sürüm imzası gerekir.

## Dosya gönderme

Uygulamayı iki cihazda açın. **Yakındaki cihazlar** listesinden alıcıyı seçin ve dosya seçin. İlk bağlantıda iki ekrandaki güvenlik kodunu karşılaştırın; eşleşiyorsa onaylayın. Alıcı dosyayı kabul ettikten sonra dosya `Downloads/LocalDrop` klasöründe görünür.

Gelecekte yayımlanacak imzalı dosyalar yalnızca [GitHub Releases](https://github.com/bevyss/LocalDrop/releases) sayfasından indirilmeli. Sürüm notundaki SHA-256 değeriyle indirdiğiniz dosyanın özetini karşılaştırın:

```powershell
Get-FileHash .\indirilen-dosya -Algorithm SHA256
```

Henüz yayımlanmış sürüm veya VirusTotal sonucu yok. Güvenlik adımları [sürüm kontrol listesinde](docs/RELEASE_SECURITY.md) açıklanmıştır.
