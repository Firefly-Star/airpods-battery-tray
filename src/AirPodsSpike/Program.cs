using System.Runtime.InteropServices;
using AirPodsSpike.Interop;

const string DefaultDevice = "EC:73:79:5A:E3:AE";

if (args.Length > 0 && args[0] is "-h" or "--help")
{
    Console.WriteLine("usage: AirPodsSpike [bluetooth-address]");
    Console.WriteLine($"       default address: {DefaultDevice}");
    return 0;
}

string device = args.Length > 0 ? args[0] : DefaultDevice;

ulong address;
try
{
    address = BtAddress.Parse(device);
}
catch (FormatException ex)
{
    Console.Error.WriteLine($"bad bluetooth address: {ex.Message}");
    return 2;
}

WinSock.WSADATA wsa;
int startup = WinSock.WSAStartup(0x0202, out wsa);
if (startup != 0)
{
    Console.Error.WriteLine($"WSAStartup failed: {startup} {WinSock.Describe(startup)}");
    return 1;
}

Console.WriteLine($"device   : {BtAddress.Format(address)}  (btAddr=0x{address:X12})");
Console.WriteLine($"winsock  : v{wsa.wVersion:X4}  sizeof(SOCKADDR_BTH)={Marshal.SizeOf<WinSock.SOCKADDR_BTH>()}");
Console.WriteLine();

var probes = new (string Id, string Note, Guid Service, uint Psm)[]
{
    ("A", "control - device's own SDP server", Guid.Empty, 0x0001),
    ("B", "target PSM, no UUID", Guid.Empty, 0x1001),
    ("C", "target PSM + AAP UUID", L2CapProbe.AapServiceClass, 0x1001),
    ("D", "AAP UUID resolved via SDP (psm=0)", L2CapProbe.AapServiceClass, 0),
};

var outcomes = new List<(string Id, ConnectResult Result)>();

foreach (var probe in probes)
{
    string service = probe.Service == Guid.Empty ? "null" : "aap ";
    Console.Write($"[{probe.Id}] psm=0x{probe.Psm:X4} guid={service}  {probe.Note}\n      -> ");

    ConnectResult result = L2CapProbe.Connect(address, probe.Service, probe.Psm, timeoutMs: 10000);
    Console.WriteLine(Describe(result));
    result.CloseSocket();
    outcomes.Add((probe.Id, result));
}

Console.WriteLine();
Console.WriteLine("--- summary ---");
foreach (var (id, result) in outcomes)
{
    Console.WriteLine($"{id}  {Describe(result)}");
}

Console.WriteLine();
Console.WriteLine($"verdict: {Verdict(outcomes)}");

WinSock.WSACleanup();
return 0;

static string Describe(ConnectResult r)
{
    string status = r.Verdict switch
    {
        ConnectVerdict.Connected => "CONNECTED",
        ConnectVerdict.SocketFailed => $"SOCKET_FAIL({r.ErrorCode} {WinSock.Describe(r.ErrorCode)})",
        ConnectVerdict.ConnectRefused => $"CONNECT_REFUSED({r.ErrorCode} {WinSock.Describe(r.ErrorCode)})",
        ConnectVerdict.ConnectTimeout => $"CONNECT_TIMEOUT({r.ErrorCode} {WinSock.Describe(r.ErrorCode)})",
        _ => $"CONNECT_FAILED({r.ErrorCode} {WinSock.Describe(r.ErrorCode)})",
    };

    if (r.Verdict == ConnectVerdict.Connected && r.ResolvedPsm != 0)
    {
        status += $" psm=0x{r.ResolvedPsm:X4}";
    }

    return $"{status}  ({r.ElapsedMs} ms)";
}

static string Verdict(List<(string Id, ConnectResult Result)> outcomes)
{
    ConnectResult a = outcomes.First(o => o.Id == "A").Result;
    bool aDone = a.Verdict == ConnectVerdict.Connected;
    bool anyTarget = outcomes.Where(o => o.Id != "A").Any(o => o.Result.Verdict == ConnectVerdict.Connected);

    if (!aDone && !anyTarget)
    {
        return "nothing reachable - suspect the btAddr encoding or the bluetooth stack, "
             + "not the AAP PSM. fix experiment A first.";
    }

    if (aDone && anyTarget)
    {
        return "user-mode L2CAP works and the AAP PSM is reachable. proceed to the handshake ladder.";
    }

    return "DEAD END SIGNAL - the SDP control connection works but every AAP PSM attempt failed. "
         + "user-mode L2CAP is fine; PSM 0x1001 specifically appears gated. "
         + "the remaining path is a kernel driver, which changes the project's scope. stop and decide.";
}
