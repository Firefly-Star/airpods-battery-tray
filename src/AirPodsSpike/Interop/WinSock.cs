using System.Runtime.InteropServices;

namespace AirPodsSpike.Interop;

internal static class WinSock
{
    public const int AF_BTH = 32;
    public const int BTHPROTO_L2CAP = 0x0100;
    public const int SOCK_STREAM = 1;

    public const int SOL_SOCKET = 0xFFFF;
    public const int SO_ERROR = 0x1007;

    public const long FIONBIO = unchecked((long)0x8004667E);

    public const short POLLRDNORM = 0x0100;
    public const short POLLRDBAND = 0x0200;
    public const short POLLIN = POLLRDNORM | POLLRDBAND;
    public const short POLLWRNORM = 0x0010;
    public const short POLLOUT = POLLWRNORM;
    public const short POLLERR = 0x0001;
    public const short POLLHUP = 0x0002;
    public const short POLLNVAL = 0x0004;

    public const int WSAEWOULDBLOCK = 10035;
    public const int WSAENETDOWN = 10050;
    public const int WSAEACCES = 10013;
    public const int WSAEINVAL = 10022;
    public const int WSAENOPROTOOPT = 10042;
    public const int WSAESOCKTNOSUPPORT = 10044;
    public const int WSAEAFNOSUPPORT = 10047;
    public const int WSAEADDRINUSE = 10048;
    public const int WSAEADDRNOTAVAIL = 10049;
    public const int WSAENETUNREACH = 10051;
    public const int WSAETIMEDOUT = 10060;
    public const int WSAECONNREFUSED = 10061;
    public const int WSAEHOSTUNREACH = 10065;

    public static readonly IntPtr InvalidSocket = new(-1);

    [StructLayout(LayoutKind.Sequential)]
    public struct WSADATA
    {
        public ushort wVersion;
        public ushort wHighVersion;
        public ushort iMaxSockets;
        public ushort iMaxUdpDg;
        public IntPtr lpVendorInfo;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 257)] public string szDescription;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 129)] public string szSystemStatus;
    }

    // Declared inside #include <pshpack1.h> in ws2bth.h; sizeof == 30.
    // Getting the packing wrong makes sizeof 40 and connect fails with WSAEADDRNOTAVAIL (10049).
    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    public struct SOCKADDR_BTH
    {
        public ushort addressFamily;
        public ulong btAddr;
        public Guid serviceClassId;
        public uint port;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct WSAPOLLFD
    {
        public IntPtr fd;
        public short events;
        public short revents;
    }

    [DllImport("ws2_32.dll")]
    public static extern int WSAStartup(ushort wVersionRequested, out WSADATA lpWSAData);

    [DllImport("ws2_32.dll")]
    public static extern int WSACleanup();

    [DllImport("ws2_32.dll")]
    public static extern IntPtr socket(int af, int type, int protocol);

    [DllImport("ws2_32.dll")]
    public static extern int connect(IntPtr s, ref SOCKADDR_BTH name, int namelen);

    [DllImport("ws2_32.dll")]
    public static extern int send(IntPtr s, byte[] buf, int len, int flags);

    [DllImport("ws2_32.dll")]
    public static extern int recv(IntPtr s, byte[] buf, int len, int flags);

    [DllImport("ws2_32.dll")]
    public static extern int ioctlsocket(IntPtr s, long cmd, ref uint argp);

    [DllImport("ws2_32.dll")]
    public static extern int getsockopt(IntPtr s, int level, int optname, ref int optval, ref int optlen);

    [DllImport("ws2_32.dll")]
    public static extern int getsockname(IntPtr s, ref SOCKADDR_BTH name, ref int namelen);

    [DllImport("ws2_32.dll")]
    public static extern int WSAPoll([In, Out] WSAPOLLFD[] fdArray, uint fds, int timeout);

    [DllImport("ws2_32.dll")]
    public static extern int closesocket(IntPtr s);

    [DllImport("ws2_32.dll")]
    public static extern int WSAGetLastError();

    public static string Describe(int error) => error switch
    {
        WSAEWOULDBLOCK => "WSAEWOULDBLOCK",
        WSAENETDOWN => "WSAENETDOWN",
        WSAEACCES => "WSAEACCES",
        WSAEINVAL => "WSAEINVAL",
        WSAENOPROTOOPT => "WSAENOPROTOOPT",
        WSAESOCKTNOSUPPORT => "WSAESOCKTNOSUPPORT",
        WSAEAFNOSUPPORT => "WSAEAFNOSUPPORT",
        WSAEADDRINUSE => "WSAEADDRINUSE",
        WSAEADDRNOTAVAIL => "WSAEADDRNOTAVAIL",
        WSAENETUNREACH => "WSAENETUNREACH",
        WSAETIMEDOUT => "WSAETIMEDOUT",
        WSAECONNREFUSED => "WSAECONNREFUSED",
        WSAEHOSTUNREACH => "WSAEHOSTUNREACH",
        0 => "ok",
        _ => "unknown",
    };
}
