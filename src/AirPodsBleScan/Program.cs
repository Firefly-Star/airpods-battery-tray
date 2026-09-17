using System.Diagnostics;
using System.Globalization;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Enumeration;
using Windows.Storage.Streams;

// Dumps raw BLE advertisements. Nothing here interprets Apple's payload layout on purpose:
// the goal is to see the actual bytes before writing any decoder against them.
const ushort AppleCompanyId = 0x004C;

// Apple Continuity message type 0x07 = "AirPods, proximity pairing" -- the one that
// carries battery. Other types seen in the wild (0x10 Nearby Info, 0x12 Find My,
// 0x0C Handoff) come from phones and watches and must not be mistaken for AirPods.
const byte AirPodsProximityType = 0x07;

int seconds = args.Length > 0
    && int.TryParse(args[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out int parsed)
    && parsed > 0
        ? parsed
        : 30;

bool waitForAirPods = args.Contains("--wait-airpods");

Console.WriteLine(waitForAirPods
    ? $"scanning up to {seconds}s, stopping early once an apple 0x{AirPodsProximityType:X2} (airpods) payload arrives"
    : $"scanning for {seconds}s, apple 0x{AppleCompanyId:X4} payloads dumped in full");

// Sanity check that the WinRT Bluetooth surface works at all before trusting a silent scan.
try
{
    DeviceInformationCollection known = await DeviceInformation.FindAllAsync(BluetoothLEDevice.GetDeviceSelector());
    Console.WriteLine($"BLE devices known to windows: {known.Count}");
    foreach (DeviceInformation device in known.OrderBy(d => d.Name, StringComparer.OrdinalIgnoreCase))
    {
        Console.WriteLine($"    {device.Name}   {device.Id}");
    }
}
catch (Exception ex)
{
    Console.Error.WriteLine($"BLE device enumeration failed: {ex.GetType().Name}: {ex.Message}");
}

Console.WriteLine();

var addressHits = new Dictionary<ulong, int>();
var distinctApplePayloads = new HashSet<string>();
var appleTypeCounts = new Dictionary<byte, int>();
var gate = new object();
var stopwatch = Stopwatch.StartNew();
int totalAdverts = 0;
bool sawAirPods = false;

BluetoothLEAdvertisementWatcher? watcher = null;

foreach (BluetoothLEScanningMode mode in new[] { BluetoothLEScanningMode.Active, BluetoothLEScanningMode.Passive })
{
    var candidate = new BluetoothLEAdvertisementWatcher { ScanningMode = mode };

    try
    {
        // Bluetooth 5 extended advertising PDUs are dropped silently without this,
        // and the setter throws on adapters that do not support the feature.
        candidate.AllowExtendedAdvertisements = true;
    }
    catch (Exception ex)
    {
        Console.Error.WriteLine($"  {mode}: AllowExtendedAdvertisements unsupported: {ex.Message}");
    }

    candidate.Received += OnReceived;
    candidate.Stopped += OnStopped;

    try
    {
        candidate.Start();
    }
    catch (Exception ex)
    {
        Console.Error.WriteLine($"  {mode}: Start() threw {ex.GetType().Name}: {ex.Message}");
        continue;
    }

    for (int i = 0; i < 15 && candidate.Status == BluetoothLEAdvertisementWatcherStatus.Created; i++)
    {
        await Task.Delay(200);
    }

    Console.WriteLine($"  {mode}: status={candidate.Status}");

    if (candidate.Status == BluetoothLEAdvertisementWatcherStatus.Started)
    {
        watcher = candidate;
        break;
    }

    candidate.Received -= OnReceived;
    candidate.Stopped -= OnStopped;
}

if (watcher is null)
{
    Console.Error.WriteLine("scanning did not start in any mode");
    return 1;
}

var heartbeat = new CancellationTokenSource();
_ = Task.Run(async () =>
{
    try
    {
        while (true)
        {
            await Task.Delay(TimeSpan.FromSeconds(15), heartbeat.Token);
            lock (gate)
            {
                Console.WriteLine($"[heartbeat] {totalAdverts} adverts, {distinctApplePayloads.Count} distinct apple payloads");
            }
        }
    }
    catch (OperationCanceledException)
    {
        // shutting down
    }
});

var deadline = stopwatch.Elapsed + TimeSpan.FromSeconds(seconds);

while (stopwatch.Elapsed < deadline)
{
    await Task.Delay(500);

    if (waitForAirPods)
    {
        bool arrived;
        lock (gate)
        {
            arrived = sawAirPods;
        }

        if (arrived)
        {
            await Task.Delay(3000);
            break;
        }
    }
}

heartbeat.Cancel();
watcher.Stop();
await Task.Delay(TimeSpan.FromMilliseconds(500));

lock (gate)
{
    Console.WriteLine();
    Console.WriteLine($"--- {totalAdverts} advertisements from {addressHits.Count} addresses ---");
    Console.WriteLine($"distinct apple payloads: {distinctApplePayloads.Count}");
    Console.WriteLine($"airpods (0x{AirPodsProximityType:X2}) payloads seen: {sawAirPods}");

    Console.WriteLine();
    Console.WriteLine("apple message types seen:");
    foreach (var entry in appleTypeCounts.OrderByDescending(e => e.Value))
    {
        Console.WriteLine($"    0x{entry.Key:X2}  x{entry.Value}");
    }
}

return sawAirPods ? 0 : 3;

void OnReceived(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs eventArgs)
{
    BluetoothLEAdvertisement ad = eventArgs.Advertisement;

    lock (gate)
    {
        totalAdverts++;
        addressHits.TryGetValue(eventArgs.BluetoothAddress, out int hits);
        addressHits[eventArgs.BluetoothAddress] = hits + 1;

        var apple = ad.ManufacturerData.Where(m => m.CompanyId == AppleCompanyId).ToList();

        if (apple.Count == 0)
        {
            if (hits == 0)
            {
                Console.WriteLine(
                    $"[{stopwatch.Elapsed.TotalSeconds,7:F2}s] {Mac(eventArgs.BluetoothAddress)}  " +
                    $"rssi={eventArgs.RawSignalStrengthInDBm,4}dBm  name='{ad.LocalName}'");
            }

            return;
        }

        byte[] first = Bytes(apple[0].Data);
        byte appleType = first.Length > 0 ? first[0] : (byte)0;

        appleTypeCounts.TryGetValue(appleType, out int typeHits);
        appleTypeCounts[appleType] = typeHits + 1;

        if (appleType == AirPodsProximityType)
        {
            sawAirPods = true;
        }

        string payload = string.Join(" | ", apple.Select(m => Hex(m.Data)));
        string key = $"{eventArgs.BluetoothAddress:X12}/{payload}";
        if (!distinctApplePayloads.Add(key))
        {
            return;
        }

        Console.WriteLine();
        Console.WriteLine($"[{stopwatch.Elapsed.TotalSeconds,7:F2}s] APPLE 0x{appleType:X2}  {Mac(eventArgs.BluetoothAddress)}  " +
                          $"rssi={eventArgs.RawSignalStrengthInDBm,4}dBm  type={eventArgs.AdvertisementType}  name='{ad.LocalName}'");
        Console.WriteLine($"    manufacturer : {payload}");

        if (appleType == AirPodsProximityType)
        {
            foreach (BluetoothLEAdvertisementDataSection section in ad.DataSections)
            {
                Console.WriteLine($"    section 0x{section.DataType:X2} : {Hex(section.Data)}");
            }

            foreach (Guid uuid in ad.ServiceUuids)
            {
                Console.WriteLine($"    service      : {uuid}");
            }
        }

        Console.WriteLine();
    }
}

void OnStopped(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementWatcherStoppedEventArgs eventArgs)
{
    if (eventArgs.Error != BluetoothError.Success)
    {
        Console.Error.WriteLine($"watcher stopped with error: {eventArgs.Error}");
    }
}

static string Mac(ulong value)
{
    string hex = value.ToString("X12", CultureInfo.InvariantCulture);
    return string.Join(":", Enumerable.Range(0, 6).Select(i => hex.Substring(i * 2, 2)));
}

static byte[] Bytes(IBuffer buffer)
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

static string Hex(IBuffer buffer)
{
    byte[] bytes = Bytes(buffer);
    return bytes.Length == 0 ? "<empty>" : Convert.ToHexString(bytes);
}
