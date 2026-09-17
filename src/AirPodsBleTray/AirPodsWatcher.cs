using Windows.Devices.Bluetooth.Advertisement;
using Windows.Storage.Streams;

namespace AirPodsBleTray;

// Advertisement is null when nothing recent has been heard. LastSeenAt is kept separately so the
// UI can say when the last reading was, instead of silently showing stale numbers as current.
internal sealed record AirPodsSnapshot(AirPodsAdvertisement? Advertisement, DateTimeOffset? LastSeenAt);

/// <summary>
/// Scans for AirPods proximity-pairing advertisements and reports the strongest recent one.
///
/// AirPods only broadcast this message while the lid is open or a bud is out of the case, so
/// there are long stretches with no data at all. They also rotate their LE address every few
/// minutes, so the tracked address cannot be pinned and is re-chosen by signal strength.
/// </summary>
internal sealed class AirPodsWatcher : IDisposable
{
    private static readonly TimeSpan Freshness = TimeSpan.FromSeconds(20);

    // Your own AirPods sit around -30..-45 dBm on a desk; anything weaker is a neighbour's and
    // would otherwise be reported as yours whenever yours goes quiet.
    private const int MinimumRssi = -55;

    private readonly BluetoothLEAdvertisementWatcher _watcher = new()
    {
        ScanningMode = BluetoothLEScanningMode.Active,
    };

    private readonly Dictionary<ulong, (DateTimeOffset Seen, AirPodsAdvertisement Ad)> _recent = new();
    private readonly HashSet<string> _logged = new();
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
    }

    public string LogPath { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "AirPodsBleTray",
        "advertisements.log");

    public void Start()
    {
        _watcher.Start();
    }

    // Called from the UI thread on a timer. Everything that touches the shared state happens
    // under the lock, so no cross-thread marshalling is needed anywhere.
    public AirPodsSnapshot Snapshot()
    {
        lock (_gate)
        {
            DateTimeOffset cutoff = DateTimeOffset.Now - Freshness;

            foreach (ulong stale in _recent.Where(entry => entry.Value.Seen < cutoff).Select(entry => entry.Key).ToList())
            {
                _recent.Remove(stale);
            }

            AirPodsAdvertisement? best = _recent.Values
                .Where(entry => entry.Ad.Rssi >= MinimumRssi)
                .OrderByDescending(entry => entry.Ad.Rssi)
                .Select(entry => entry.Ad)
                .FirstOrDefault();

            return new AirPodsSnapshot(best, _lastSeenAt);
        }
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
                _recent[advertisement.Address] = (DateTimeOffset.Now, advertisement);
                _lastSeenAt = DateTimeOffset.Now;
                LogOnce(advertisement);
            }
        }
    }

    // Keyed on the plaintext prefix only: the trailing bytes are encrypted and differ on every
    // single advertisement, so keying on the whole payload would log continuously.
    private void LogOnce(AirPodsAdvertisement advertisement)
    {
        string key = $"{advertisement.Address:X12}/{Convert.ToHexString(advertisement.Raw.AsSpan(0, 11))}";
        if (!_logged.Add(key))
        {
            return;
        }

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
            File.AppendAllText(
                LogPath,
                $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff} {advertisement.Mac()} rssi={advertisement.Rssi,4} " +
                $"{Convert.ToHexString(advertisement.Raw)}{Environment.NewLine}");
        }
        catch (IOException)
        {
            // Logging is best effort; a locked or unwritable log must not break scanning.
        }
        catch (UnauthorizedAccessException)
        {
        }
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
        _watcher.Received -= OnReceived;

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
