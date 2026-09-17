using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Storage.Streams;

namespace AirPodsBleTray;

internal sealed record AirPodsSnapshot(
    string? ModelName,
    int? Left,
    int? Right,
    int? Case,
    bool LeftCharging,
    bool RightCharging,
    bool CaseCharging,
    DateTimeOffset? LastSeenAt,
    AirPodsAdvertisement? Freshest);

/// <summary>
/// Watches for AirPods battery broadcasts and folds the two earbuds' advertisements into one state.
///
/// Two things make this harder than it looks:
///
///   * Every AirPods in range broadcasts the same packet shape, and they rotate their LE address
///     every few minutes so a device cannot be pinned by address. Devices are told apart the same
///     way AirPodsDesktop does it: on an address change the model must match, the reported levels
///     may move by at most one step, and the signal must not jump by more than 50 dBm.
///
///   * BluetoothLEAdvertisementWatcher is known to stop delivering events and go Aborted. It never
///     throws; it just goes quiet. The only recovery is to start it again, so a watchdog does that.
/// </summary>
internal sealed class AirPodsWatcher : IDisposable
{
    private static readonly TimeSpan LostAfter = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan RetryInterval = TimeSpan.FromSeconds(3);
    private const int MinimumRssi = -60;
    private const int MaximumRssiJump = 50;
    private const int MaximumLevelJump = 1;

    private sealed class TrackedSide
    {
        public AirPodsAdvertisement? Advertisement;
        public DateTimeOffset SeenAt;
    }

    private readonly BluetoothLEAdvertisementWatcher _watcher = new();
    private readonly TrackedSide[] _sides = { new(), new() }; // 0 = left, 1 = right
    private readonly HashSet<string> _logged = new();
    private readonly System.Threading.Timer _restartTimer;
    private readonly object _gate = new();
    private DateTimeOffset? _lastSeenAt;
    private bool _disposed;

    public AirPodsWatcher()
    {
        try
        {
            // Bluetooth 5 extended advertising PDUs are dropped silently without this.
            _watcher.AllowExtendedAdvertisements = true;
        }
        catch (Exception)
        {
            // Adapter does not support it; legacy scanning still works.
        }

        _watcher.Received += OnReceived;
        _watcher.Stopped += OnStopped;
        _restartTimer = new System.Threading.Timer(_ => Restart(), null, Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
    }

    public string LogPath { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "AirPodsBleTray",
        "advertisements.log");

    public void Start()
    {
        try
        {
            _watcher.Start();
        }
        catch (Exception ex)
        {
            Log($"start failed: {ex.GetType().Name}: {ex.Message}");
        }
    }

    // Called from the UI thread on a timer. All shared state is touched under the lock, so no
    // cross-thread marshalling is needed anywhere.
    public AirPodsSnapshot Snapshot()
    {
        lock (_gate)
        {
            DateTimeOffset cutoff = DateTimeOffset.Now - LostAfter;
            foreach (TrackedSide side in _sides.Where(s => s.Advertisement is not null && s.SeenAt < cutoff))
            {
                side.Advertisement = null;
            }

            AirPodsAdvertisement? model = Pick(side => side.ModelId != 0);
            AirPodsAdvertisement? left = Pick(side => side.LeftBattery is not null);
            AirPodsAdvertisement? right = Pick(side => side.RightBattery is not null);
            AirPodsAdvertisement? box = Pick(side => side.CaseBattery is not null);

            return new AirPodsSnapshot(
                model?.ModelName,
                left?.LeftBattery,
                right?.RightBattery,
                box?.CaseBattery,
                left?.LeftCharging ?? false,
                right?.RightCharging ?? false,
                box?.CaseCharging ?? false,
                _lastSeenAt,
                Pick(_ => true));
        }
    }

    // Of the two earbuds' latest advertisements, prefer the one holding the field we want,
    // and when both have it, the more recent one.
    private AirPodsAdvertisement? Pick(Func<AirPodsAdvertisement, bool> hasField)
    {
        TrackedSide? best = null;

        foreach (TrackedSide side in _sides)
        {
            if (side.Advertisement is not { } advertisement || !hasField(advertisement))
            {
                continue;
            }

            if (best is null || side.SeenAt > best.SeenAt)
            {
                best = side;
            }
        }

        return best?.Advertisement;
    }

    private void OnReceived(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs args)
    {
        foreach (BluetoothLEManufacturerData manufacturer in args.Advertisement.ManufacturerData)
        {
            if (manufacturer.CompanyId != AirPodsAdvertisement.AppleCompanyId)
            {
                continue;
            }

            byte[] data = ReadBuffer(manufacturer.Data);
            if (!AirPodsAdvertisement.TryParse(args.BluetoothAddress, args.RawSignalStrengthInDBm, data, out AirPodsAdvertisement advertisement))
            {
                continue;
            }

            lock (_gate)
            {
                if (!LooksLikeOurs(advertisement))
                {
                    continue;
                }

                TrackedSide side = _sides[advertisement.BroadcastFromLeft ? 0 : 1];
                side.Advertisement = advertisement;
                side.SeenAt = DateTimeOffset.Now;
                _lastSeenAt = DateTimeOffset.Now;
                LogOnce(advertisement);
            }
        }
    }

    // Every AirPods nearby sends this same packet. The address rotates, so when it changes we have
    // to decide whether this is still our device or a stranger's: the model has to match, the
    // levels cannot have moved more than one step (they only ever move one step at a time), and
    // the signal cannot jump implausibly.
    private bool LooksLikeOurs(AirPodsAdvertisement advertisement)
    {
        if (advertisement.Rssi < MinimumRssi)
        {
            return false;
        }

        TrackedSide side = _sides[advertisement.BroadcastFromLeft ? 0 : 1];
        TrackedSide other = _sides[advertisement.BroadcastFromLeft ? 1 : 0];

        if (side.Advertisement is { } previous)
        {
            if (previous.Address != advertisement.Address)
            {
                if (previous.ModelId != advertisement.ModelId)
                {
                    return false;
                }

                if (LevelJump(previous.LeftBattery, advertisement.LeftBattery) > MaximumLevelJump ||
                    LevelJump(previous.RightBattery, advertisement.RightBattery) > MaximumLevelJump ||
                    LevelJump(previous.CaseBattery, advertisement.CaseBattery) > MaximumLevelJump)
                {
                    return false;
                }
            }

            if (Math.Abs(previous.Rssi - advertisement.Rssi) > MaximumRssiJump)
            {
                return false;
            }
        }

        if (other.Advertisement is { } otherAdv &&
            Math.Abs(otherAdv.Rssi - advertisement.Rssi) > MaximumRssiJump)
        {
            return false;
        }

        return true;
    }

    private static int LevelJump(int? a, int? b) =>
        a is null || b is null ? 0 : Math.Abs(a.Value - b.Value) / 10;

    // Keyed on the plaintext prefix: the trailing bytes are an encrypted/hashed blob that differs
    // on every single advertisement, so keying on the whole payload would log continuously.
    private void LogOnce(AirPodsAdvertisement advertisement)
    {
        string key = $"{advertisement.Address:X12}/{Convert.ToHexString(advertisement.Raw.AsSpan(0, 11))}";
        if (!_logged.Add(key))
        {
            return;
        }

        Log($"{advertisement.Mac()} rssi={advertisement.Rssi,4} " +
            $"L={Show(advertisement.LeftBattery)} R={Show(advertisement.RightBattery)} C={Show(advertisement.CaseBattery)} " +
            $"from={(advertisement.BroadcastFromLeft ? "L" : "R")} {Convert.ToHexString(advertisement.Raw)}");
    }

    private void Log(string message)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
            File.AppendAllText(LogPath, $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff} {message}{Environment.NewLine}");
        }
        catch (IOException)
        {
            // Logging is best effort; a locked or unwritable log must not break scanning.
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private static string Show(int? value) => value is null ? "--" : value.Value.ToString();

    // The watcher goes Aborted on its own and stays silent; restarting it is the only recovery.
    private void OnStopped(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementWatcherStoppedEventArgs args)
    {
        if (_disposed)
        {
            return;
        }

        Log($"watcher stopped: {args.Error}; restarting in {RetryInterval.TotalSeconds}s");
        _restartTimer.Change(RetryInterval, Timeout.InfiniteTimeSpan);
    }

    private void Restart()
    {
        if (_disposed)
        {
            return;
        }

        Start();
    }

    private static byte[] ReadBuffer(IBuffer buffer)
    {
        if (buffer is null || buffer.Length == 0)
        {
            return Array.Empty<byte>();
        }

        var reader = DataReader.FromBuffer(buffer);
        var bytes = new byte[buffer.Length];
        reader.ReadBytes(bytes);
        return bytes;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _restartTimer.Dispose();
        _watcher.Received -= OnReceived;
        _watcher.Stopped -= OnStopped;

        try
        {
            _watcher.Stop();
        }
        catch (Exception)
        {
            // Already stopped or the radio went away; nothing useful to do.
        }
    }
}
