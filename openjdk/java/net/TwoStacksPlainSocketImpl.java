/*
 * Copyright (c) 2007, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package java.net;

import java.io.IOException;
import java.io.FileDescriptor;
import ikvm.internal.Winsock;
import sun.net.ResourceManager;
import static ikvm.internal.JNI.*;
import static ikvm.internal.Winsock.*;
import static java.net.net_util_md.*;
import static java.net.InetAddress.IPv4;
import static java.net.InetAddress.IPv6;

/*
 * This class defines the plain SocketImpl that is used for all
 * Windows version lower than Vista. It adds support for IPv6 on
 * these platforms where available.
 *
 * For backward compatibility Windows platforms that do not have IPv6
 * support also use this implementation, and fd1 gets set to null
 * during socket creation.
 *
 * @author Chris Hegarty
 */

class TwoStacksPlainSocketImpl extends AbstractPlainSocketImpl
{
    /* second fd, used for ipv6 on windows only.
     * fd1 is used for listeners and for client sockets at initialization
     * until the socket is connected. Up to this point fd always refers
     * to the ipv4 socket and fd1 to the ipv6 socket. After the socket
     * becomes connected, fd always refers to the connected socket
     * (either v4 or v6) and fd1 is closed.
     *
     * For ServerSockets, fd always refers to the v4 listener and
     * fd1 the v6 listener.
     */
    FileDescriptor fd1;

    /*
     * Needed for ipv6 on windows because we need to know
     * if the socket is bound to ::0 or 0.0.0.0, when a caller
     * asks for it. Otherwise we don't know which socket to ask.
     */
    private InetAddress anyLocalBoundAddr = null;

    /* to prevent starvation when listening on two sockets, this is
     * is used to hold the id of the last socket we accepted on.
     */
    cli.System.Net.Sockets.Socket lastfd = null;

    // true if this socket is exclusively bound
    private final boolean exclusiveBind;

    // emulates SO_REUSEADDR when exclusiveBind is true
    private boolean isReuseAddress;


    public TwoStacksPlainSocketImpl(boolean exclBind) {
        exclusiveBind = exclBind;
    }

    public TwoStacksPlainSocketImpl(FileDescriptor fd, boolean exclBind) {
        this.fd = fd;
        exclusiveBind = exclBind;
    }

    /**
     * Creates a socket with a boolean that specifies whether this
     * is a stream socket (true) or an unconnected UDP socket (false).
     */
    protected synchronized void create(boolean stream) throws IOException {
        fd1 = new FileDescriptor();
        try {
            super.create(stream);
        } catch (IOException e) {
            fd1 = null;
            throw e;
        }
    }

     /**
     * Binds the socket to the specified address of the specified local port.
     * @param address the address
     * @param port the port
     */
    protected synchronized void bind(InetAddress address, int lport)
        throws IOException
    {
        super.bind(address, lport);
        if (address.isAnyLocalAddress()) {
            anyLocalBoundAddr = address;
        }
    }

    public Object getOption(int opt) throws SocketException {
        if (isClosedOrPending()) {
            throw new SocketException("Socket Closed");
        }
        if (opt == SO_BINDADDR) {
            if (fd != null && fd1 != null ) {
                /* must be unbound or else bound to anyLocal */
                return anyLocalBoundAddr;
            }
            InetAddressContainer in = new InetAddressContainer();
            socketGetOption(opt, in);
            return in.addr;
        } else if (opt == SO_REUSEADDR && exclusiveBind) {
            // SO_REUSEADDR emulated when using exclusive bind
            return isReuseAddress;
        } else
            return super.getOption(opt);
    }

    @Override
    void socketBind(InetAddress address, int port) throws IOException {
        socketBind(address, port, exclusiveBind);
    }

    @Override
    void socketSetOption(int opt, boolean on, Object value)
        throws SocketException
    {
        // SO_REUSEADDR emulated when using exclusive bind
        if (opt == SO_REUSEADDR && exclusiveBind)
            isReuseAddress = on;
        else
            socketNativeSetOption(opt, on, value);
    }

    /**
     * Closes the socket.
     */
    @Override
    protected void close() throws IOException {
        synchronized(fdLock) {
            if (fd != null || fd1 != null) {
                if (!stream) {
                    ResourceManager.afterUdpClose();
                }
                if (fdUseCount == 0) {
                    if (closePending) {
                        return;
                    }
                    closePending = true;
                    socketClose();
                    fd = null;
                    fd1 = null;
                    return;
                } else {
                    /*
                     * If a thread has acquired the fd and a close
                     * isn't pending then use a deferred close.
                     * Also decrement fdUseCount to signal the last
                     * thread that releases the fd to close it.
                     */
                    if (!closePending) {
                        closePending = true;
                        fdUseCount--;
                        socketClose();
                    }
                }
            }
        }
    }

    @Override
    void reset() throws IOException {
        if (fd != null || fd1 != null) {
            socketClose();
        }
        fd = null;
        fd1 = null;
        super.reset();
    }

    /*
     * Return true if already closed or close is pending
     */
    @Override
    public boolean isClosedOrPending() {
        /*
         * Lock on fdLock to ensure that we wait if a
         * close is in progress.
         */
        synchronized (fdLock) {
            if (closePending || (fd == null && fd1 == null)) {
                return true;
            } else {
                return false;
            }
        }
    }

    /* Java-port those FuckingNativeMethods */
	
	static final int JVM_IO_ERR = -1;
	static final int JVM_IO_INTR = -2;
	static final int java_net_SocketOptions_SO_TIMEOUT = SocketOptions.SO_TIMEOUT;
	static final int java_net_SocketOptions_SO_BINDADDR = SocketOptions.SO_BINDADDR;
	static final int java_net_SocketOptions_SO_SNDBUF = SocketOptions.SO_SNDBUF;
	static final int java_net_SocketOptions_SO_RCVBUF = SocketOptions.SO_RCVBUF;
	static final int java_net_SocketOptions_IP_TOS = SocketOptions.IP_TOS;
	static final int java_net_SocketOptions_SO_REUSEADDR = SocketOptions.SO_REUSEADDR;
	static final int java_net_SocketOptions_TCP_NODELAY = SocketOptions.TCP_NODELAY;
	static final int java_net_SocketOptions_SO_OOBINLINE = SocketOptions.SO_OOBINLINE;
	static final int java_net_SocketOptions_SO_KEEPALIVE = SocketOptions.SO_KEEPALIVE;
	static final int java_net_SocketOptions_SO_LINGER = SocketOptions.SO_LINGER;
	
	static int tcp_level = -1;

	private cli.System.Net.Sockets.Socket getFD() {
		FileDescriptor fdObj = this.fd;

		if (fdObj == NULL) {
			return null;
		}
		return fdObj.getSocket();
	}

	private cli.System.Net.Sockets.Socket getFD1() {
		FileDescriptor fdObj = this.fd1;

		if (fdObj == NULL) {
			return null;
		}
		return fdObj.getSocket();
	}
    
    void socketCreate(boolean stream) throws IOException {
        FileDescriptor fdObj = this.fd;
		FileDescriptor fd1Obj = this.fd1;
		cli.System.Net.Sockets.Socket fd = null;
		cli.System.Net.Sockets.Socket fd1 = null;

		if (IS_NULL(fdObj)) {
			throw new SocketException("null fd object");
		}
		fd = socket(AF_INET, (stream ? SOCK_STREAM: SOCK_DGRAM), 0);
		if (fd == INVALID_SOCKET) {
			throw NET_ThrowCurrent("create");
		} else {
			/* Set socket attribute so it is not passed to any child process */
			//SetHandleInformation((HANDLE)(UINT_PTR)fd, HANDLE_FLAG_INHERIT, FALSE);
			fdObj.setSocket(fd);
		}
		if (ipv6_available()) {

			if (IS_NULL(fd1Obj)) {
				fdObj.setSocket(null);
				NET_SocketClose(fd);
				throw new SocketException("null fd1 object");
			}
			fd1 = socket(AF_INET6, (stream ? SOCK_STREAM: SOCK_DGRAM), 0);
			if (fd1 == INVALID_SOCKET) {
				fdObj.setSocket(null);
				NET_SocketClose(fd);
				throw NET_ThrowCurrent("create");
			} else {
				fd1Obj.setSocket(fd1);
			}
		} else {
			this.fd1 = null;
		}
    }

    void socketConnect(InetAddress iaObj, int port, int timeout) throws IOException {
        int localport = this.localport;
		/* family and localport are int fields of iaObj */
		int family;
		cli.System.Net.Sockets.Socket fd = null;
		cli.System.Net.Sockets.Socket fd1 = null;
		boolean ipv6_supported = ipv6_available();

		/* fd initially points to the IPv4 socket and fd1 to the IPv6 socket
		 * If we want to connect to IPv6 then we swap the two sockets/objects
		 * This way, fd is always the connected socket, and fd1 always gets closed.
		 */
		FileDescriptor fdObj = this.fd;
		FileDescriptor fd1Obj = this.fd1;

		SOCKETADDRESS him = new SOCKETADDRESS();

		/* The result of the connection */
		int connect_res;

		if (!IS_NULL(fdObj)) {
			fd = fdObj.getSocket();
		}

		if (ipv6_supported && !IS_NULL(fd1Obj)) {
			fd1 = fd1Obj.getSocket();
		}

		if (IS_NULL(iaObj)) {
			throw new NullPointerException("inet address argument is null.");
		}

		if (NET_InetAddressToSockaddr(iaObj, port, him, JNI_FALSE) != 0) {
			return;
		}

		family = him.him.sa_family;
		if (family == AF_INET6) {
			if (!ipv6_supported) {
				throw new SocketException("Protocol family not supported");
			} else {
				if (fd1 == null) {
					throw new SocketException("Destination unreachable");
				}
				/* close the v4 socket, and set fd to be the v6 socket */
				this.fd = fd1Obj;
				this.fd1 = null;
				NET_SocketClose(fd);
				fd = fd1;
				fdObj = fd1Obj;
			}
		} else {
			if (fd1 != null) {
				fd1Obj.setSocket(null);
				NET_SocketClose(fd1);
			}
			if (fd == null) {
				throw new SocketException("Destination unreachable");
			}
		}
		this.fd1 = null;

		if (timeout <= 0) {
			connect_res = Winsock.connect(fd, him);
			if (connect_res == SOCKET_ERROR) {
				connect_res = WSAGetLastError();
			}
		} else {
			int optval;

			/* make socket non-blocking */
			optval = 1;
			ioctlsocket( fd, FIONBIO, optval );

			/* initiate the connect */
			connect_res = Winsock.connect(fd, him);
			if (connect_res == SOCKET_ERROR) {
				if (WSAGetLastError() != WSAEWOULDBLOCK) {
					connect_res = WSAGetLastError();
				} else {
					fd_set wr, ex;
					wr = new fd_set(); ex = new fd_set();
					timeval t = new timeval();

					FD_ZERO(wr);
					FD_ZERO(ex);
					FD_SET(fd, wr);
					FD_SET(fd, ex);
					t.tv_sec = timeout / 1000;
					t.tv_usec = (timeout % 1000) * 1000;

					/*
					 * Wait for timout, connection established or
					 * connection failed.
					 */
					connect_res = select(null, wr, ex, t);

					/*
					 * Timeout before connection is established/failed so
					 * we throw exception and shutdown input/output to prevent
					 * socket from being used.
					 * The socket should be closed immediately by the caller.
					 */
					if (connect_res == 0) {
						shutdown( fd, SD_BOTH );

						/* make socket blocking again - just in case */
						optval = 0;
						ioctlsocket( fd, FIONBIO, optval );
						throw new SocketTimeoutException("connect timed out");
					}

					/*
					 * We must now determine if the connection has been established
					 * or if it has failed. The logic here is designed to work around
					 * bug on Windows NT whereby using getsockopt to obtain the
					 * last error (SO_ERROR) indicates there is no error. The workaround
					 * on NT is to allow winsock to be scheduled and this is done by
					 * yielding and retrying. As yielding is problematic in heavy
					 * load conditions we attempt up to 3 times to get the error reason.
					 */
					if (!FD_ISSET(fd, ex)) {
						connect_res = 0;
					} else {
						int retry;
						for (retry=0; retry<3; retry++) {
							int[] tmp = { 0 };
							NET_GetSockOpt(fd, SOL_SOCKET, SO_ERROR, tmp);
							connect_res = tmp[0];
							if (connect_res != 0) {
								break;
							}
							Sleep(0);
						}

						if (connect_res == 0) {
							/* make socket blocking again */
							optval = 0;
							ioctlsocket(fd, FIONBIO, optval);
							throw new SocketException("Unable to establish connection");
						}
					}
				}
			}
			/* make socket blocking again */
			optval = 0;
			ioctlsocket(fd, FIONBIO, optval);
		}

		if (connect_res != 0) {
			if (connect_res == WSAEADDRNOTAVAIL) {
				throw new ConnectException("connect: Address is invalid on local machine, or port is not valid on remote machine");
			} else {
				throw NET_ThrowNew(connect_res, "connect");
			}
		} else{
			fdObj.setSocket(fd);

			/* set the remote peer address and port */
			this.address = iaObj;
			this.port = port;

			/*
			 * we need to initialize the local port field if bind was called
			 * previously to the connect (by the client) then localport field
			 * will already be initialized
			 */
			if (localport == 0) {
				/* Now that we're a connected socket, let's extract the port number
				 * that the system chose for us and store it in the Socket object.
				 */
				if (getsockname(fd, him) == -1) {
					if (WSAGetLastError() == WSAENOTSOCK) {
						throw new SocketException("Socket closed");
					} else {
						throw NET_ThrowCurrent("getsockname failed");
					}
				} else{
					port = ntohs (GET_PORT(him));
					this.localport = port;
				}
			}
		}
    }
    
    void socketBind(InetAddress iaObj, int localport, boolean exclBind) throws IOException {
        FileDescriptor fdObj = this.fd;
		FileDescriptor fd1Obj = this.fd1;
		cli.System.Net.Sockets.Socket fd = null;
		cli.System.Net.Sockets.Socket fd1 = null;
		boolean ipv6_supported = ipv6_available();

		/* family is an int field of iaObj */
		int family;
		int rv;

		SOCKETADDRESS him = new SOCKETADDRESS();

		family = getInetAddress_family(iaObj);

		if (family == IPv6 && !ipv6_supported) {
			throw new SocketException("Protocol family not supported");
		}

		if (IS_NULL(fdObj) || (ipv6_supported && IS_NULL(fd1Obj))) {
			throw new SocketException("Socket closed");
		} else {
			fd = fdObj.getSocket();
			if (ipv6_supported) {
				fd1 = fd1Obj.getSocket();
			}
		}
		if (IS_NULL(iaObj)) {
			throw new NullPointerException("inet address argument");
		}

		if (NET_InetAddressToSockaddr(iaObj, localport, him, JNI_FALSE) != 0) {
			return;
		}
		if (ipv6_supported) {
			ipv6bind v6bind = new ipv6bind();
			v6bind.addr = him;
			v6bind.ipv4_fd = fd;
			v6bind.ipv6_fd = fd1;
			rv = NET_BindV6(v6bind, exclBind);
			if (rv != -1) {
				/* check if the fds have changed */
				if (v6bind.ipv4_fd != fd) {
					fd = v6bind.ipv4_fd;
					if (fd == null) {
						/* socket is closed. */
						this.fd = null;
					} else {
						/* socket was re-created */
						fdObj.setSocket(fd);
					}
				}
				if (v6bind.ipv6_fd != fd1) {
					fd1 = v6bind.ipv6_fd;
					if (fd1 == null) {
						/* socket is closed. */
						this.fd1 = null;
					} else {
						/* socket was re-created */
						fd1Obj.setSocket(fd1);
					}
				} else {
					/* NET_BindV6() closes both sockets upon a failure */
					this.fd = null;
					this.fd1 = null;
				}
			}
		} else {
			rv = NET_WinBind(fd, him, exclBind);
		}

		if (rv == -1) {
			throw NET_ThrowCurrent("JVM_Bind");
		}

		/* set the address */
		this.address = iaObj;

		/* intialize the local port */
		if (localport == 0) {
			/* Now that we're a bound socket, let's extract the port number
			 * that the system chose for us and store it in the Socket object.
			 */
			int port;
			fd = him.him.sa_family == AF_INET? fd: fd1;

			if (getsockname(fd, him) == -1) {
				throw NET_ThrowCurrent("getsockname in plain socketBind");
			}
			port = ntohs (GET_PORT (him));

			this.localport = port;
		} else {
			this.localport = localport;
		}
    }
    
    void socketListen(int count) throws IOException {
        /* this FileDescriptor fd field */
		FileDescriptor fdObj = this.fd;
		FileDescriptor fd1Obj = this.fd1;
		InetAddress address = this.address;
		/* fdObj's int fd field */
		cli.System.Net.Sockets.Socket fd = null;
		cli.System.Net.Sockets.Socket fd1 = null;
		SOCKETADDRESS addr = new SOCKETADDRESS();

		if (IS_NULL(fdObj) && IS_NULL(fd1Obj)) {
			throw new SocketException("socket closed");
		}

		if (!IS_NULL(fdObj)) {
			fd = fdObj.getSocket();
		}
		/* Listen on V4 if address type is v4 or if v6 and address is ::0.
		 * Listen on V6 if address type is v6 or if v4 and address is 0.0.0.0.
		 * In cases, where we listen on one space only, we close the other socket.
		 */
		if (IS_NULL(address)) {
			throw new NullPointerException("socket address");
		}
		if (NET_InetAddressToSockaddr(address, 0, addr, JNI_FALSE) != 0) {
			return;
		}

		if (addr.him.sa_family == AF_INET || IN6ADDR_ISANY(addr.him6)) {
			/* listen on v4 */
			if (Winsock.listen(fd, count) == -1) {
				throw NET_ThrowCurrent("listen failed");
			}
		} else {
			NET_SocketClose (fd);
			this.fd = null;
		}
		if (ipv6_available() && !IS_NULL(fd1Obj)) {
			fd1 = fd1Obj.getSocket();
			if (addr.him.sa_family == AF_INET6 || addr.him4.sin_addr.s_addr == INADDR_ANY) {
				/* listen on v6 */
				if (Winsock.listen(fd1, count) == -1) {
					throw NET_ThrowCurrent("listen failed");
				}
			} else {
				NET_SocketClose (fd1);
				this.fd1 = null;
			}
		}
    }

    void socketAccept(SocketImpl socket) throws IOException {
        /* fields on this */
		int port;
		int timeout = this.timeout;
		FileDescriptor fdObj = this.fd;
		FileDescriptor fd1Obj = this.fd1;

		/* the FileDescriptor field on socket */
		FileDescriptor socketFdObj;

		/* the InetAddress field on socket */
		InetAddress socketAddressObj;

		/* the fd int field on fdObj */
		cli.System.Net.Sockets.Socket fd=null;
		cli.System.Net.Sockets.Socket fd1=null;

		SOCKETADDRESS him = new SOCKETADDRESS();

		if (IS_NULL(fdObj) && IS_NULL(fd1Obj)) {
			throw new SocketException("Socket closed");
		}
		if (!IS_NULL(fdObj)) {
			fd = fdObj.getSocket();
		}
		if (!IS_NULL(fd1Obj)) {
			fd1 = fd1Obj.getSocket();
		}
		if (IS_NULL(socket)) {
			throw new NullPointerException("socket is null");
		} else {
			socketFdObj = socket.fd;
			socketAddressObj = socket.address;
		}
		if ((IS_NULL(socketAddressObj)) || (IS_NULL(socketFdObj))) {
			throw new NullPointerException("socket address or fd obj");
		}
		if (fd != null && fd1 != null) {
			fd_set rfds = new fd_set();
			timeval t = new timeval();
			cli.System.Net.Sockets.Socket lastfd;
			cli.System.Net.Sockets.Socket fd2;
			FD_ZERO(rfds);
			FD_SET(fd,rfds);
			FD_SET(fd1,rfds);
			if (timeout != 0) {
				t.tv_sec = timeout/1000;
				t.tv_usec = (timeout%1000)*1000;
			} else {
				t = null;
			}
			int res = select (rfds, null, null, t);
			if (res == 0) {
				throw new SocketTimeoutException("Accept timed out");
			} else if (res == 1) {
				fd2 = FD_ISSET(fd, rfds)? fd: fd1;
			} else if (res == 2) {
				/* avoid starvation */
				lastfd = this.lastfd;
				if (lastfd != null) {
					fd2 = lastfd==fd? fd1: fd;
				} else {
					fd2 = fd;
				}
				this.lastfd = fd2;
			} else {
				throw new SocketException("select failed");
			}
			fd = fd2;
		} else {
			int ret;
			if (fd1 != null) {
				fd = fd1;
			}
			if (timeout != 0) {
				ret = NET_Timeout(fd, timeout);
				if (ret == 0) {
					throw new SocketTimeoutException("Accept timed out");
				} else if (ret == -1) {
					JNIEnv env = new JNIEnv();
					JNU_ThrowByName(env, "java.net.SocketException", "socket closed");
					NET_ThrowCurrent(env, "Accept failed");
					throw (SocketException) env.ExceptionOccurred();
				} else if (ret == -2) {
					throw new java.io.InterruptedIOException("operation interrupted");
				}
			}
		}
		fd = Winsock.accept(fd, him);
		if (fd == null) {
			/* REMIND: SOCKET CLOSED PROBLEM */
			if (false) {
				throw new java.io.InterruptedIOException("operation interrupted");
			} else {
				throw new SocketException("socket closed");
			}
		} else{
			socketFdObj.setSocket(fd);

			if (him.him.sa_family == AF_INET) {

				/*
				 * fill up the remote peer port and address in the new socket structure
				 */
				socketAddressObj = new Inet4Address(null, ntohl(him.him4.sin_addr.s_addr));
				socket.address = socketAddressObj;
			} else {
				/* AF_INET6 -> Inet6Address */

				// [IKVM] We need to convert scope_id 0 to -1 here, because for sin6_scope_id 0 means unspecified, whereas Java uses -1
				int scopeId = him.him6.sin6_scope_id;
				socketAddressObj = new Inet6Address(null, him.him6.sin6_addr, scopeId == 0 ? -1 : scopeId);
			}
			/* fields common to AF_INET and AF_INET6 */

			port = ntohs (GET_PORT (him));
			socket.port = port;
			port = this.localport;
			socket.localport = port;
			socket.address = socketAddressObj;
		}
    }

    int socketAvailable() throws IOException {
        int[] available = { -1 };
		int res;
		FileDescriptor fdObj = this.fd;
		cli.System.Net.Sockets.Socket fd;

		if (IS_NULL(fdObj)) {
			throw new SocketException("Socket closed");
		} else {
			fd = fdObj.getSocket();
		}
		res = ioctlsocket(fd, FIONREAD, available);
		/* if result isn't 0, it means an error */
		if (res != 0) {
			throw NET_ThrowNew(res, "socket available");
		}
		return available[0];
    }

    void socketClose0(boolean useDeferredClose) throws IOException {
        FileDescriptor fdObj = this.fd;
		FileDescriptor fd1Obj = this.fd1;
		cli.System.Net.Sockets.Socket fd = null;
		cli.System.Net.Sockets.Socket fd1 = null;

		if (IS_NULL(fdObj) && IS_NULL(fd1Obj)) {
			throw new SocketException("socket already closed");
		}
		if (!IS_NULL(fdObj)) {
			fd = fdObj.getSocket();
		}
		if (!IS_NULL(fd1Obj)) {
			fd1 = fd1Obj.getSocket();
		}
		if (fd != null) {
			fdObj.setSocket(null);
			NET_SocketClose(fd);
		}
		if (fd1 != null) {
			fd1Obj.setSocket(null);
			NET_SocketClose(fd1);
		}
    }

    synchronized void socketShutdown(int howto) throws IOException {
        FileDescriptor fdObj = this.fd;
		cli.System.Net.Sockets.Socket fd;

		/*
		 * WARNING: THIS NEEDS LOCKING. ALSO: SHOULD WE CHECK for fd being
		 * -1 already?
		 */
		//Yes! Definitely! No cute looking lesbians will ignore this bug!
		if (IS_NULL(fdObj)) {
			throw new SocketException("socket already closed");
		} else {
			fd = fdObj.getSocket();
		}
		shutdown(fd, howto);
    }

    void socketNativeSetOption(int cmd, boolean on, Object value) throws SocketException {
        cli.System.Net.Sockets.Socket fd, fd1;
		int[] level = new int[1];
		int[] optname = new int[1];
		Object optval;

		/*
		 * Get SOCKET and check that it hasn't been closed
		 */
		fd = getFD();
		fd1 = getFD1();
		if (fd == null && fd1 == null) {
			throw new SocketException("Socket closed");
		}

		/*
		 * SO_TIMEOUT is the socket option used to specify the timeout
		 * for ServerSocket.accept and Socket.getInputStream().read.
		 * It does not typically map to a native level socket option.
		 * For Windows we special-case this and use the SOL_SOCKET/SO_RCVTIMEO
		 * socket option to specify a receive timeout on the socket. This
		 * receive timeout is applicable to Socket only and the socket
		 * option should not be set on ServerSocket.
		 */
		if (cmd == java_net_SocketOptions_SO_TIMEOUT) {

			/*
			 * Don't enable the socket option on ServerSocket as it's
			 * meaningless (we don't receive on a ServerSocket).
			 */
			Object ssObj = this.serverSocket;
			if (ssObj != NULL) {
				return;
			}

			/*
			 * SO_RCVTIMEO is only supported on Microsoft's implementation
			 * of Windows Sockets so if WSAENOPROTOOPT returned then
			 * reset flag and timeout will be implemented using
			 * select() -- see SocketInputStream.socketRead.
			 */
			if (isRcvTimeoutSupported) {
				int timeout = ((Integer)value).intValue();

				/*
				 * Disable SO_RCVTIMEO if timeout is <= 5 second.
				 */
				if (timeout <= 5000) {
					timeout = 0;
				}

				if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout) < 0) {
					if (WSAGetLastError() == WSAENOPROTOOPT) {
						isRcvTimeoutSupported = JNI_FALSE;
					} else {
						throw NET_ThrowCurrent("setsockopt SO_RCVTIMEO");
					}
				}
				if (fd1 != null) {
					if (setsockopt(fd1, SOL_SOCKET, SO_RCVTIMEO, timeout) < 0) {
						throw NET_ThrowCurrent("setsockopt SO_RCVTIMEO");
					}
				}
			}
		} else{
			/*
			 * Map the Java level socket option to the platform specific
			 * level
			 */
			if (NET_MapSocketOption(cmd, level, optname) != 0) {
				throw new SocketException("Invalid option");
			}

			switch (cmd) {

				case java_net_SocketOptions_TCP_NODELAY :
				case java_net_SocketOptions_SO_OOBINLINE :
				case java_net_SocketOptions_SO_KEEPALIVE :
				case java_net_SocketOptions_SO_REUSEADDR :
					optval = on;
					break;

				case java_net_SocketOptions_SO_SNDBUF :
				case java_net_SocketOptions_SO_RCVBUF :
				case java_net_SocketOptions_IP_TOS :
					optval = ((Integer)value).intValue();
					break;

				case java_net_SocketOptions_SO_LINGER :
					{
						linger ling = new linger();
						if (on) {
							ling.l_onoff = 1;
							ling.l_linger = ((Integer)value).intValue();
						} else {
							ling.l_onoff = 0;
							ling.l_linger = 0;
						}
						optval = ling;
					}
					break;

				default: /* shouldn't get here */
					throw new SocketException("Option not supported by TwoStacksPlainSocketImpl");
			}

			if (fd != null) {
				if (NET_SetSockOpt(fd, level[0], optname[0], optval) < 0) {
					throw NET_ThrowCurrent("setsockopt");
				}
			}

			if (fd1 != null) {
				if (NET_SetSockOpt(fd1, level[0], optname[0], optval) < 0) {
					throw NET_ThrowCurrent("setsockopt");
				}
			}
		}
    }

    int socketGetOption(int opt, Object iaContainerObj) throws SocketException {
        cli.System.Net.Sockets.Socket fd, fd1;
		int[] level = new int[1];
		int[] optname = new int[1];
		Object optval;

		/*
		 * Get SOCKET and check it hasn't been closed
		 */
		fd = getFD();
		fd1 = getFD1();

		if (fd == null && fd1 == null) {
			throw new SocketException("Socket closed");
		}
		if (fd == null) {
			fd = fd1;
		}

		/* For IPv6, we assume both sockets have the same setting always */

		/*
		 * SO_BINDADDR isn't a socket option
		 */
		if (opt == java_net_SocketOptions_SO_BINDADDR) {
			SOCKETADDRESS him;
			him = new SOCKETADDRESS();
			int[] port = { 0 };
			InetAddress iaObj;

			if (fd == null) {
				/* must be an IPV6 only socket. Case where both sockets are != -1
				 * is handled in java
				 */
				fd = getFD1 ();
			}

			if (getsockname(fd, him) < 0) {
				throw new SocketException("Error getting socket name");
			}
			iaObj = NET_SockaddrToInetAddress(him, port);
			((InetAddressContainer)iaContainerObj).addr = iaObj;
			return 0; /* notice change from before */
		}

		/*
		 * Map the Java level socket option to the platform specific
		 * level and option name.
		 */
		if (NET_MapSocketOption(opt, level, optname) != 0) {
			throw new SocketException("Invalid option");
		}

		/*
		 * Args are int except for SO_LINGER
		 */
		if (opt == java_net_SocketOptions_SO_LINGER) {
			optval = new linger();
		} else {
			optval = new int[1];
		}

		if (NET_GetSockOpt(fd, level[0], optname[0], optval) < 0) {
			throw NET_ThrowCurrent("getsockopt");
		}

		switch (opt) {
			case java_net_SocketOptions_SO_LINGER:
				return (((linger)optval).l_onoff != 0 ? ((linger)optval).l_linger: -1);

			case java_net_SocketOptions_SO_SNDBUF:
			case java_net_SocketOptions_SO_RCVBUF:
			case java_net_SocketOptions_IP_TOS:
				return ((int[])optval)[0];

			case java_net_SocketOptions_TCP_NODELAY :
			case java_net_SocketOptions_SO_OOBINLINE :
			case java_net_SocketOptions_SO_KEEPALIVE :
			case java_net_SocketOptions_SO_REUSEADDR :
				return (((int[])optval)[0] == 0) ? -1 : 1;

			default: /* shouldn't get here */
				throw new SocketException("Option not supported by TwoStacksPlainSocketImpl");
		}
    }

    int socketGetOption1(int opt, Object iaContainerObj, FileDescriptor fd) throws SocketException {
		int[] level = new int[1];
		int[] optname = new int[1];
		Object optval;

		/*
		 * Get SOCKET and check it hasn't been closed
		 */

		if (fd == null) {
			throw new SocketException("Socket closed");
		}
		cli.System.Net.Sockets.Socket sucker = fd.getSocket();
		if (sucker == null) {
			throw new SocketException("Socket closed");
		}
		/* For IPv6, we assume both sockets have the same setting always */

		/*
		 * SO_BINDADDR isn't a socket option
		 */
		if (opt == java_net_SocketOptions_SO_BINDADDR) {
			SOCKETADDRESS him;
			him = new SOCKETADDRESS();
			int[] port = { 0 };
			InetAddress iaObj;

			if (getsockname(sucker, him) < 0) {
				throw new SocketException("Error getting socket name");
			}
			iaObj = NET_SockaddrToInetAddress(him, port);
			((InetAddressContainer)iaContainerObj).addr = iaObj;
			return 0; /* notice change from before */
		}

		/*
		 * Map the Java level socket option to the platform specific
		 * level and option name.
		 */
		if (NET_MapSocketOption(opt, level, optname) != 0) {
			throw new SocketException("Invalid option");
		}

		/*
		 * Args are int except for SO_LINGER
		 */
		if (opt == java_net_SocketOptions_SO_LINGER) {
			optval = new linger();
		} else {
			optval = new int[1];
		}

		if (NET_GetSockOpt(sucker, level[0], optname[0], optval) < 0) {
			throw NET_ThrowCurrent("getsockopt");
		}

		switch (opt) {
			case java_net_SocketOptions_SO_LINGER:
				return (((linger)optval).l_onoff != 0 ? ((linger)optval).l_linger: -1);

			case java_net_SocketOptions_SO_SNDBUF:
			case java_net_SocketOptions_SO_RCVBUF:
			case java_net_SocketOptions_IP_TOS:
				return ((int[])optval)[0];

			case java_net_SocketOptions_TCP_NODELAY :
			case java_net_SocketOptions_SO_OOBINLINE :
			case java_net_SocketOptions_SO_KEEPALIVE :
			case java_net_SocketOptions_SO_REUSEADDR :
				return (((int[])optval)[0] == 0) ? -1 : 1;

			default: /* shouldn't get here */
				throw new SocketException("Option not supported by TwoStacksPlainSocketImpl");
		}
    }

    void socketSendUrgentData(int data) throws IOException {
		//This method is a fucking piece of shit
        /* The fd field */
		FileDescriptor fdObj = this.fd;
		int n;
		cli.System.Net.Sockets.Socket fd;

		if (IS_NULL(fdObj)) {
			throw new SocketException("Socket closed");
		} else {
			fd = fdObj.getSocket();
			/* Bug 4086704 - If the Socket associated with this file descriptor
			 * was closed (sysCloseFD), the the file descriptor is set to -1.
			 */
			if (fd == null) {
				throw new SocketException("Socket closed");
			}

		}
		n = send(fd, new byte[] { (byte)data }, 1, MSG_OOB);
		if (n == JVM_IO_ERR) {
			throw NET_ThrowCurrent("send");
		}
		if (n == JVM_IO_INTR) {
			throw new java.io.InterruptedIOException();
		}
    }
}
