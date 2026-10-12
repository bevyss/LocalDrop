using System.Collections.ObjectModel;
using System.Diagnostics;
using LocalDrop.Core;
using LocalDrop_Windows.Discovery;
using LocalDrop_Windows.Transfer;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;

namespace LocalDrop_Windows;

public sealed partial class MainPage : Page
{
    private readonly ObservableCollection<PeerItem> _peers = new();
    private readonly ObservableCollection<ReceivedFileItem> _received = new();
    private DiscoveryService? _discovery;
    private TransferEngine? _transfer;
    private TaskCompletionSource<bool>? _promptResult;
    private string? _deviceId;
    private string _deviceName = "";
    private bool _languageReady;

    private static string SettingsDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LocalDrop");
    private static string DownloadDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "LocalDrop");

    public MainPage()
    {
        InitializeComponent();
        PeerList.ItemsSource = _peers;
        ReceivedFileList.ItemsSource = _received;
        foreach (var (code, name) in new[] { ("en", "English"), ("tr", "Türkçe"),
            ("de", "Deutsch"), ("es", "Español"), ("zh", "中文"), ("ja", "日本語") })
            LanguageBox.Items.Add(new ComboBoxItem { Content = name, Tag = code });
        LanguageBox.SelectedIndex = Array.IndexOf(new[] { "en", "tr", "de", "es", "zh", "ja" }, Localization.Language);
        _languageReady = true;
        ApplyLanguage();
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        ((MainWindow)((App)Application.Current).MainWindow!).ConfigureTitleBar(TitleBar);
        try
        {
            _deviceId = DeviceIdentity.LoadOrCreate(Path.Combine(SettingsDirectory, "device-id"));
            _deviceName = DeviceName.LoadOrDefault(Path.Combine(SettingsDirectory, "device-name"),
                Environment.MachineName);
            StartServices();
            RefreshReceivedFiles();
            AppLog.Write("info", "startup");
        }
        catch (Exception ex)
        {
            StatusText.Text = Localization.Text("discovery_failed");
            AppLog.Write("error", "startup_failed", ex.ToString());
            StopServices();
        }
    }

    private void StartServices()
    {
        if (_deviceId is null) return;
        OwnDeviceText.Text = Localization.Text("this_device", _deviceName, "Windows");
        SidebarDeviceName.Text = _deviceName;
        DeviceNameBox.Text = _deviceName;
        _transfer = new TransferEngine(_deviceId, _deviceName, ConfirmPeerAsync, AcceptFileAsync,
            message => DispatcherQueue.TryEnqueue(() => TransferStatusText.Text = message),
            () => DispatcherQueue.TryEnqueue(RefreshReceivedFiles));
        _discovery = new DiscoveryService(_deviceId, _transfer.ReceiveAsync);
        _discovery.PeerSeen += peer => DispatcherQueue.TryEnqueue(() =>
        {
            var existing = _peers.FirstOrDefault(item => item.Id == peer.Id);
            var description = $"{PeerTypeLabel(peer.Type)} · {Localization.Text("online")}";
            if (existing is not null && existing.Peer == peer && existing.Description == description) return;
            var wasSelected = existing is not null && ReferenceEquals(PeerList.SelectedItem, existing);
            if (existing is not null) _peers.Remove(existing);
            var item = new PeerItem(peer, description);
            _peers.Add(item);
            if (wasSelected) PeerList.SelectedItem = item;
            StatusText.Text = Localization.Text("device_count", _peers.Count);
            AppLog.Write("info", "peer_seen", peer.Id);
        });
        _discovery.PeerLost += id => DispatcherQueue.TryEnqueue(() =>
        {
            var existing = _peers.FirstOrDefault(item => item.Id == id);
            if (existing is not null) _peers.Remove(existing);
            StatusText.Text = _peers.Count == 0 ? Localization.Text("no_devices") : Localization.Text("device_count", _peers.Count);
            AppLog.Write("info", "peer_lost", id);
        });
        _discovery.Error += error => DispatcherQueue.TryEnqueue(() =>
        {
            StatusText.Text = error;
            AppLog.Write("error", "discovery_error", error);
        });
        _discovery.Start(_deviceName);
        StatusText.Text = Localization.Text("searching");
    }

    private void StopServices()
    {
        _discovery?.Dispose();
        _discovery = null;
        _transfer?.Dispose();
        _transfer = null;
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        CompletePrompt(false);
        StopServices();
    }

    private void ShowSection(FrameworkElement section)
    {
        NearbyPanel.Visibility = section == NearbyPanel ? Visibility.Visible : Visibility.Collapsed;
        FilesPanel.Visibility = section == FilesPanel ? Visibility.Visible : Visibility.Collapsed;
        SettingsPanel.Visibility = section == SettingsPanel ? Visibility.Visible : Visibility.Collapsed;
    }

    private void NearbyNav_Click(object sender, RoutedEventArgs e) => ShowSection(NearbyPanel);
    private void FilesNav_Click(object sender, RoutedEventArgs e)
    {
        RefreshReceivedFiles();
        ShowSection(FilesPanel);
    }
    private void SettingsNav_Click(object sender, RoutedEventArgs e) => ShowSection(SettingsPanel);

    private void RefreshFiles_Click(object sender, RoutedEventArgs e) => RefreshReceivedFiles();

    private void RefreshReceivedFiles()
    {
        _received.Clear();
        try
        {
            if (Directory.Exists(DownloadDirectory))
                foreach (var file in new DirectoryInfo(DownloadDirectory).EnumerateFiles()
                    .Where(file => !file.Name.EndsWith(".partial", StringComparison.OrdinalIgnoreCase))
                    .OrderByDescending(file => file.LastWriteTimeUtc).Take(100))
                    _received.Add(new ReceivedFileItem(file.Name, file.FullName,
                        $"{FormatSize(file.Length)} · {file.LastWriteTime:g}", Localization.Text("open")));
            FilesEmptyText.Visibility = _received.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        catch (Exception ex)
        {
            FilesEmptyText.Text = Localization.Text("files_failed");
            FilesEmptyText.Visibility = Visibility.Visible;
            AppLog.Write("error", "received_files_failed", ex.ToString());
        }
    }

    private async void SendFile_Click(object sender, RoutedEventArgs e)
    {
        if (PeerList.SelectedItem is not PeerItem selected || _transfer is null)
        {
            TransferStatusText.Text = Localization.Text("select_peer");
            return;
        }
        if (selected.Peer.Capabilities != "file1")
        {
            TransferStatusText.Text = Localization.Text("unsupported_peer");
            return;
        }
        try
        {
            var picker = new FileOpenPicker();
            picker.FileTypeFilter.Add("*");
            var window = ((App)Application.Current).MainWindow!;
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(window));
            var file = await picker.PickSingleFileAsync();
            if (file is not null) await _transfer.SendAsync(selected.Peer, file.Path);
        }
        catch (Exception ex)
        {
            TransferStatusText.Text = Localization.Text("send_failed");
            AppLog.Write("error", "send_start_failed", ex.ToString());
        }
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        CompletePrompt(false);
        _transfer?.Cancel();
    }

    private void OpenDownloads_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Directory.CreateDirectory(DownloadDirectory);
            Process.Start(new ProcessStartInfo(DownloadDirectory) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            TransferStatusText.Text = Localization.Text("folder_failed");
            AppLog.Write("error", "open_downloads_failed", ex.ToString());
        }
    }

    private void OpenFile_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string path } || !File.Exists(path)) return;
        try { Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }); }
        catch (Exception ex)
        {
            FilesEmptyText.Text = Localization.Text("open_failed");
            FilesEmptyText.Visibility = Visibility.Visible;
            AppLog.Write("error", "open_received_file_failed", ex.ToString());
        }
    }

    private void SaveName_Click(object sender, RoutedEventArgs e)
    {
        if (_transfer?.IsBusy == true)
        {
            SettingsStatusText.Text = Localization.Text("busy_name");
            return;
        }
        try
        {
            var name = DeviceName.Normalize(DeviceNameBox.Text);
            if (name == _deviceName)
            {
                SettingsStatusText.Text = Localization.Text("name_unchanged");
                return;
            }
            DeviceName.Save(Path.Combine(SettingsDirectory, "device-name"), name);
            StopServices();
            _peers.Clear();
            _deviceName = name;
            StartServices();
            SettingsStatusText.Text = Localization.Text("name_saved");
            AppLog.Write("info", "device_name_changed");
        }
        catch (ArgumentException) { SettingsStatusText.Text = Localization.Text("name_invalid"); }
        catch (Exception ex)
        {
            SettingsStatusText.Text = Localization.Text("name_failed");
            AppLog.Write("error", "device_name_save_failed", ex.ToString());
        }
    }

    private Task<bool> ConfirmPeerAsync(string peerName, string code) => ShowDecisionAsync(
        Localization.Text("secure_connection"), Localization.Text("confirm_code", peerName), Localization.Text("codes_match"), code);

    private Task<bool> AcceptFileAsync(string sender, string fileName, long length) => ShowDecisionAsync(
        Localization.Text("incoming_file"), $"{Localization.Text("wants_to_send", sender)}\n\n{fileName}\n\n{FormatSize(length)}", Localization.Text("accept"), null);

    private static string FormatSize(long bytes) => bytes switch
    {
        < 1024 => $"{bytes:N0} B",
        < 1024 * 1024 => $"{bytes / 1024.0:F1} KiB",
        < 1024L * 1024 * 1024 => $"{bytes / 1048576.0:F1} MiB",
        _ => $"{bytes / 1073741824.0:F1} GiB"
    };

    private Task<bool> ShowDecisionAsync(string title, string message, string acceptLabel, string? code)
    {
        var result = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!DispatcherQueue.TryEnqueue(() =>
        {
            CompletePrompt(false);
            _promptResult = result;
            PromptTitle.Text = title;
            PromptMessage.Text = message;
            PromptCode.Text = code ?? "";
            PromptCode.Visibility = code is null ? Visibility.Collapsed : Visibility.Visible;
            PromptAcceptButton.Content = acceptLabel;
            PromptOverlay.Visibility = Visibility.Visible;
        })) result.TrySetResult(false);
        return result.Task;
    }

    private void PromptAccept_Click(object sender, RoutedEventArgs e) => CompletePrompt(true);
    private void PromptDecline_Click(object sender, RoutedEventArgs e) => CompletePrompt(false);

    private void LanguageBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_languageReady || LanguageBox.SelectedItem is not ComboBoxItem { Tag: string language }) return;
        Localization.SetLanguage(language);
        ApplyLanguage();
        var selectedId = (PeerList.SelectedItem as PeerItem)?.Id;
        var peers = _peers.Select(item => item.Peer).ToArray();
        _peers.Clear();
        foreach (var peer in peers) _peers.Add(new PeerItem(peer, $"{PeerTypeLabel(peer.Type)} · {Localization.Text("online")}"));
        PeerList.SelectedItem = _peers.FirstOrDefault(item => item.Id == selectedId);
        if (_deviceName.Length > 0) OwnDeviceText.Text = Localization.Text("this_device", _deviceName, "Windows");
        StatusText.Text = _peers.Count == 0 ? Localization.Text("no_devices") : Localization.Text("device_count", _peers.Count);
        RefreshReceivedFiles();
    }

    private void ApplyLanguage()
    {
        TaglineText.Text = Localization.Text("tagline");
        WorkspaceText.Text = Localization.Text("workspace");
        NearbyNavButton.Content = NearbyTitle.Text = Localization.Text("nearby");
        FilesNavButton.Content = FilesTitle.Text = Localization.Text("files");
        SettingsNavButton.Content = SettingsTitle.Text = Localization.Text("settings");
        LocalNetworkText.Text = Localization.Text("local_network");
        EncryptedText.Text = Localization.Text("encrypted");
        ChooseDeviceText.Text = Localization.Text("choose_device");
        SendButton.Content = Localization.Text("send_file");
        CancelButton.Content = Localization.Text("cancel_transfer");
        FilesSubtitle.Text = Localization.Text("files_subtitle");
        RefreshButton.Content = Localization.Text("refresh");
        OpenFolderButton.Content = Localization.Text("open_folder");
        FilesEmptyText.Text = Localization.Text("no_files");
        DeviceNameLabel.Text = Localization.Text("device_name");
        DeviceNameBox.PlaceholderText = Localization.Text("device_name_hint");
        DeviceNameHelp.Text = Localization.Text("device_name_help");
        SaveNameButton.Content = Localization.Text("save_name");
        LanguageLabel.Text = Localization.Text("language");
        DownloadsTitle.Text = Localization.Text("downloads");
        DownloadsHelp.Text = Localization.Text("downloads_help");
        OpenDownloadsButton.Content = Localization.Text("open_downloads");
        PromptDeclineButton.Content = Localization.Text("decline");
        StatusText.Text = Localization.Text("starting");
    }

    private static string PeerTypeLabel(string type) => type switch
    {
        "android" => "Android",
        "windows" => "Windows",
        _ => type
    };

    private void CompletePrompt(bool accepted)
    {
        var result = _promptResult;
        _promptResult = null;
        PromptOverlay.Visibility = Visibility.Collapsed;
        result?.TrySetResult(accepted);
    }
}

public sealed record PeerItem(DiscoveryRecord Peer, string Description)
{
    public string Id => Peer.Id;
    public string Name => Peer.Name;
}

public sealed record ReceivedFileItem(string Name, string Path, string Details, string OpenLabel);
