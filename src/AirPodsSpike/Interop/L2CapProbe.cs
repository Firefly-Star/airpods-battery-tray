using System.Diagnostics;
using System.Runtime.InteropServices;

namespace AirPodsSpike.Interop;

internal enum ConnectVerdict
{
    Connected,
    SocketFailed,
    ConnectRefused,
    ConnectTimeout,
    ConnectFailed,
}

internal readonly record struct ConnectResult(
    ConnectVerdict Verdict,
    int ErrorCode,
    long ElapsedMs,
    IntPtr Socket,
    uint ResolvedPsm)
{
    public void CloseSocket()
    {
        if (Socket != WinSock.InvalidSocket)
        {
            WinSock.closesocket(Socket);
        }
    }
}

internal static class L2CapProbe
{
    public const uint AapPsm = 0x1001;
    public static readonly Guid AapServiceClass = new("74ec2172-0bad-4d01-8f77-997b2be0722a");

    public static ConnectResult Connect(ulong address, Guid serviceClassId, uint psm, int timeoutMs)
    {
        var sw = Stopwatch.StartNew();

        IntPtr s = WinSock.socket(WinSock.AF_BTH, WinSock.SOCK_STREAM, WinSock.BTHPROTO_L2CAP);
        if (s == WinSock.InvalidSocket)
        {
            return new(ConnectVerdict.SocketFailed, WinSock.WSAGetLastError(), sw.ElapsedMilliseconds,
                WinSock.InvalidSocket, 0);
        }

        var sockaddr = new WinSock.SOCKADDR_BTH
        {
            addressFamily = WinSock.AF_BTH,
            btAddr = address,
            serviceClassId = serviceClassId,
            port = psm,
        };
        int namelen = Marshal.SizeOf<WinSock.SOCKADDR_BTH>();

        // connect() blocks here: it pages the device and may run an SDP query. Run it on a
        // dedicated thread and bound the wait. WSAGetLastError is per-thread, so it must be
        // read on the thread that made the failing call, not after we come back.
        int rc = -1;
        int err = 0;
        var worker = Task.Factory.StartNew(
            () =>
            {
                var local = sockaddr;
                rc = WinSock.connect(s, ref local, namelen);
                if (rc != 0)
                {
                    err = WinSock.WSAGetLastError();
                }
            },
            CancellationToken.None,
            TaskCreationOptions.LongRunning,
            TaskScheduler.Default);

        if (!worker.Wait(timeoutMs))
        {
            WinSock.closesocket(s);
            return new(ConnectVerdict.ConnectTimeout, WinSock.WSAETIMEDOUT, sw.ElapsedMilliseconds,
                WinSock.InvalidSocket, 0);
        }

        if (rc != 0)
        {
            WinSock.closesocket(s);
            return new(Classify(err), err, sw.ElapsedMilliseconds, WinSock.InvalidSocket, 0);
        }

        return new(ConnectVerdict.Connected, 0, sw.ElapsedMilliseconds, s, ResolveRemotePsm(s));
    }

    public static bool SetNonBlocking(IntPtr s)
    {
        uint enabled = 1;
        return WinSock.ioctlsocket(s, WinSock.FIONBIO, ref enabled) == 0;
    }

    // With port == 0 Windows resolves the PSM from the service class UUID via SDP. Reading it
    // back tells us which PSM the record actually names, instead of us assuming 0x1001.
    private static uint ResolveRemotePsm(IntPtr s)
    {
        var name = new WinSock.SOCKADDR_BTH();
        int len = Marshal.SizeOf<WinSock.SOCKADDR_BTH>();
        return WinSock.getsockname(s, ref name, ref len) == 0 ? name.port : 0;
    }

    private static ConnectVerdict Classify(int error) => error switch
    {
        WinSock.WSAECONNREFUSED => ConnectVerdict.ConnectRefused,
        WinSock.WSAETIMEDOUT => ConnectVerdict.ConnectTimeout,
        _ => ConnectVerdict.ConnectFailed,
    };
}
