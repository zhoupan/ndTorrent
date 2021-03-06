package com.ndtorrent.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ndtorrent.client.status.ConnectionInfo;
import com.ndtorrent.client.status.PieceInfo;
import com.ndtorrent.client.status.StatusObserver;
import com.ndtorrent.client.status.TorrentInfo;
import com.ndtorrent.client.status.TrackerInfo;
import com.ndtorrent.client.tracker.Event;
import com.ndtorrent.client.tracker.Session;

public final class Peer extends Thread {
	static final int MAX_CHANNELS = 80;
	static final long SECOND = (long) 1e9;

	private volatile boolean stop_requested;

	private MetaInfo meta;
	private Torrent torrent;
	private ClientInfo client_info;
	private Socket socket; // reusable address for outgoing connections
	private Selector channel_selector;
	private Selector socket_selector;

	private Queue<BTSocket> pending = new ConcurrentLinkedQueue<BTSocket>();
	private List<PeerChannel> channels = new LinkedList<PeerChannel>();
	private List<Session> sessions = new ArrayList<Session>();
	private Map<String, Long> updated_sessions = new HashMap<String, Long>();

	private Set<String> active_ips = new HashSet<String>();
	private Set<InetSocketAddress> known = new LinkedHashSet<InetSocketAddress>();

	// External observers receive local DTO messages (i.e. GUI).
	private List<StatusObserver> observers = new CopyOnWriteArrayList<StatusObserver>();

	// Estimated time of torrent arrival / completion.
	private long eta;
	private long eta_timeout;

	public Peer(ClientInfo client_info, MetaInfo meta_info) {
		super("PEER-THREAD");

		this.client_info = client_info;
		this.meta = meta_info;
		torrent = new Torrent(meta_info, client_info.getStorageLocation());

		String announce = meta.getAnnounce();
		List<String> trackers = meta.getAnnounceList();
		if (trackers.isEmpty() && announce != null) {
			trackers.add(announce);
		}
		for (String url : trackers) {
			sessions.add(Session.create(url, client_info, meta.getInfoHash()));
		}

		sessions.add(Session.create("udp://tracker.openbittorrent.com:80",
				client_info, meta.getInfoHash()));
	}

	public void close() {
		stop_requested = true;
	}

	@Override
	public void run() {
		try {
			socket = new Socket();
			socket.setReuseAddress(true);
			socket.bind(null);
			channel_selector = Selector.open();
			socket_selector = Selector.open();
			torrent.open();
		} catch (IOException e) {
			e.printStackTrace();
			stop_requested = true;
		}

		long last_time = 0;

		while (!stop_requested) {
			try {
				// High priority //
				removeBrokenSockets();
				socket_selector.selectedKeys().clear();
				socket_selector.selectNow();
				processConnectOperations();
				processHandshakeMessages();

				removeBrokenChannels();
				configureChannelKeys();
				channel_selector.selectedKeys().clear();
				channel_selector.select(100);

				processIncomingMessages();
				processOutgoingMessages();
				requestMoreBlocks();
				// cancelEndGameRequests();
				requestEndGameBlocks();

				// Low priority //
				// Operations that are performed once per second.
				long now = System.nanoTime();
				if (now - last_time < SECOND)
					continue;

				last_time = now;

				registerPendingSockets();
				removeFellowSeeders();
				cancelDelayedRequests();
				restoreBrokenRequests();
				// restoreRejectedPieces();
				updateAmInterestedState();
				choking();
				advertiseAvailablePieces();
				keepConnectionsAlive();

				updateTrackerSessions();
				updateKnownAddresses();
				spawnOutgoingConnections();

				notifyStatusObservers();

				rollTotals();

			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		closeConnections();

		torrent.close();

	}

	private void updateTrackerSessions() {
		long now = System.nanoTime();
		for (Session session : sessions) {
			if (session.isUpdating() || session.isUpdateError())
				continue;

			long interval = now - session.updatedAt();
			if (interval < session.getInterval() * SECOND)
				continue;

			Event event = session.lastEvent();
			if (event == null)
				event = Event.STARTED;
			else if (event == Event.STARTED && session.isValidResponse())
				event = Event.REGULAR;

			session.update(event, 0, 0, torrent.getRemainingLength());
		}
	}

	private void updateKnownAddresses() {
		for (Session session : sessions) {
			if (session.isUpdating() || session.isUpdateError())
				continue;
			String url = session.getUrl();
			Long updated_at = updated_sessions.get(url);
			if (updated_at != null && updated_at.equals(session.updatedAt()))
				continue;
			updated_sessions.put(url, session.updatedAt());
			for (InetSocketAddress address : session.getPeers()) {
				known.add(address);
			}
		}
	}

	private void registerPendingSockets() {
		for (BTSocket socket : pending) {
			try {
				socket.register(socket_selector, SelectionKey.OP_CONNECT
						| SelectionKey.OP_READ | SelectionKey.OP_WRITE, socket);
			} catch (ClosedChannelException e) {
			}
		}
		pending.clear();
	}

	private void processConnectOperations() {
		for (SelectionKey key : socket_selector.selectedKeys()) {
			if (!key.isValid() || !key.isConnectable())
				continue;
			BTSocket socket = (BTSocket) key.attachment();
			socket.finishConnect();
		}
	}

	private void processHandshakeMessages() {
		for (SelectionKey key : socket_selector.selectedKeys()) {
			if (!key.isValid() || key.isConnectable())
				continue;
			BTSocket socket = (BTSocket) key.attachment();
			if (key.isReadable() && socket.hasInputHandshake()) {
				key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
			}
			if (key.isWritable() && !socket.hasOutputHandshake()) {
				// We assume that socket's send buffer is empty at this
				// phase, and the whole handshake message will be written.
				key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
				socket.setOutputHandshake(new HandshakeMsg(client_info.getID(),
						meta.getInfoHash()));
			}
			socket.processHandshakeMessages();
			if (socket.isHandshakeDone()) {
				key.cancel();
				if (socket.isHandshakeSuccessful())
					addReadyConnection(socket);
				else
					socket.close();
			}
		}
	}

	private void removeBrokenSockets() {
		for (SelectionKey key : socket_selector.keys()) {
			BTSocket socket = (BTSocket) key.attachment();
			if (socket.isHandshakeExpired() || socket.isError()
					|| !socket.isOpen()) {

				key.cancel();
				socket.close();
			}
		}
	}

	private void configureChannelKeys() {
		// ? To avoid filling up the memory with too many pieces,
		// disable OP_READ if Torrent writer is busy.
		for (SelectionKey key : channel_selector.keys()) {
			if (!key.isValid())
				continue;
			PeerChannel channel = (PeerChannel) key.attachment();
			if (channel.hasOutgoingMessages())
				key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
			else
				key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
		}
	}

	public boolean addIncomingConnection(final BTSocket socket) {
		if (!socket.hasInputHandshake())
			return false;
		HandshakeMsg msg = socket.getInputHandshake();
		if (msg.getInfoHash().equals(meta.getInfoHash())) {
			return pending.add(socket);
		}
		return false;
	}

	private void addReadyConnection(BTSocket socket) {
		// ? keep every address (unique IPs) that we can't accept
		// due to max connections limit, for future outgoing connections.
		if (channels.size() >= MAX_CHANNELS) {
			socket.close();
			return;
		}

		// Multiple connections with same IP are not allowed.
		// An accidental cyclic connection will be terminated
		// since one of the two end-points will be closed.
		String ip = socket.getRemoteIP();
		for (PeerChannel channel : channels) {
			// incoming counter
			// outgoing counter
			if (ip.equals(channel.socket.getRemoteIP())) {
				socket.close();
				return;
			}
		}

		PeerChannel channel = new PeerChannel();
		channel.socket = socket;
		channel.setAmInitiator(socket.getLocalPort() == this.socket
				.getLocalPort());
		channel.addBitfield(torrent.getAvailablePieces(), torrent.numPieces());

		try {
			socket.register(channel_selector, SelectionKey.OP_READ, channel);
			channels.add(channel);
		} catch (IOException e) {
			e.printStackTrace();
			socket.close();
		}
	}

	private void processIncomingMessages() {
		for (SelectionKey key : channel_selector.selectedKeys()) {
			if (!key.isValid() || !key.isReadable())
				continue;
			PeerChannel channel = (PeerChannel) key.attachment();
			channel.processIncomingMessages();
			while (channel.hasUnprocessedIncoming()) {
				Message m = channel.takeUnprocessedIncoming();
				if (m.isPiece())
					torrent.saveBlock(m);
				else if (m.isBlockRequest())
					channel.addPiece(torrent.loadBlock(m));
				else {
					channel.socket.close();
					break;
				}
			}
		}
	}

	private void processOutgoingMessages() {
		for (SelectionKey key : channel_selector.selectedKeys()) {
			if (!key.isValid() || !key.isWritable())
				continue;
			PeerChannel channel = (PeerChannel) key.attachment();
			channel.processOutgoingMessages();
		}
	}

	private void cancelEndGameRequests() {
		boolean end = !torrent.hasUnregisteredPieces();
		boolean seed = torrent.isSeed();
		if (!end || seed)
			return;

		Collection<Piece> partial_entries = torrent.getPartialPieces();
		for (PeerChannel channel : channels) {
			// channel.cancelRequestsExcept(partial_entries);
			channel.cancelAvailableBlocks(partial_entries);
		}
	}

	private void closeConnections() {
		for (BTSocket socket : pending) {
			socket.close();
		}
		pending.clear();

		for (SelectionKey key : socket_selector.keys()) {
			BTSocket socket = (BTSocket) key.attachment();
			socket.close();
		}

		for (PeerChannel channel : channels) {
			channel.socket.close();
		}
		channels.clear();

		try {
			socket.close();
			channel_selector.close();
			socket_selector.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	private void removeBrokenChannels() {
		// TODO remove when we're not seeding and:
		// 1. X minutes passed since last time we're interested in them
		// 2. we're choked for ~45 minutes
		long now = System.nanoTime();
		Iterator<PeerChannel> iter = channels.iterator();
		while (iter.hasNext()) {
			PeerChannel channel = iter.next();
			long last_input = channel.socket.lastInputMessageAt();
			long last_output = channel.socket.lastOutputMessageAt();
			boolean expired = now - Math.max(last_input, last_output) > 135 * 1e9;
			boolean is_error = channel.socket.isError();
			if (is_error || expired || !channel.socket.isOpen()) {
				// Registered sockets that get closed will eventually be removed
				// by the selector.
				channel.socket.close();
				iter.remove();
			}
		}
	}

	private void removeFellowSeeders() {
		if (!torrent.isSeed())
			return;

		BitSet available = torrent.getAvailablePieces();
		for (PeerChannel channel : channels) {
			if (channel.hasPieces(available))
				channel.socket.close();
		}
	}

	private void cancelDelayedRequests() {
		for (PeerChannel channel : channels) {
			final Piece TIMED_OUT = null;
			channel.cancelPendingRequests(TIMED_OUT, null);
		}
	}

	private void restoreBrokenRequests() {
		// If a block is flagged as requested but no channel has a corresponding
		// unfulfilled request, it's considered broken and must be restored.
		Collection<Piece> partial_entries = torrent.getPartialPieces();
		for (Piece piece : partial_entries) {
			BitSet requests = new BitSet(piece.numBlocks());
			for (PeerChannel channel : channels) {
				requests.or(channel.getPendingRequests(piece));
			}
			piece.restorePendingRequests(requests);
		}
	}

	private void spawnOutgoingConnections() {
		int nsockets = socket_selector.keys().size();
		int nchannels = channels.size();
		if (nsockets + nchannels >= MAX_CHANNELS)
			return;
		if (known.isEmpty())
			return;

		Iterator<InetSocketAddress> iter = known.iterator();
		SocketAddress remote = iter.next();
		iter.remove();
		BTSocket socket = null;
		try {
			socket = new BTSocket(this.socket.getLocalSocketAddress());
			socket.connect(remote);
			pending.add(socket);
		} catch (IOException e) {
			if (socket != null)
				socket.close();
		}

	}

	private void advertiseAvailablePieces() {
		BitSet available = torrent.getAvailablePieces();
		for (PeerChannel channel : channels) {
			channel.advertise(available);
		}
	}

	private void updateAmInterestedState() {
		for (PeerChannel channel : channels) {
			channel.updateAmInterested();
		}
	}

	private void rollTotals() {
		for (PeerChannel channel : channels) {
			channel.socket.rollTotals();
			channel.rollBlocksTotal();
		}
	}

	private void choking() {
		if (torrent.isSeed())
			Choking.updateAsSeed(channels);
		else
			Choking.updateAsLeech(channels);
	}

	private void requestMoreBlocks() {
		// Blocks of the same piece can be requested from different channels.
		// The number of channels that will contribute to a particular piece
		// depends on how many requests each channel can pipeline.

		if (torrent.isSeed() || !torrent.hasUnregisteredPieces())
			return;

		// When we begin downloading, multiple random pieces may be selected.
		boolean begin = !torrent.hasAvailablePieces();

		Collection<Piece> partial_entries = torrent.getPartialPieces();
		for (PeerChannel channel : channels) {
			if (channel.amChoked() || !channel.amInterested())
				continue;
			for (int priority = 0; priority <= 1; priority++)
				for (Piece piece : partial_entries) {
					if (!channel.canRequestMore())
						break;
					int index = piece.getIndex();
					if (channel.hasPiece(index)) {
						if (priority == 0 && !channel.participatedIn(index))
							continue;
						channel.addMaximumRequests(piece,
								piece.getNotRequested());
					}
				}
			if (channel.canRequestMore()) {
				int index = begin ? selectRandomPiece(channel)
						: selectRarePiece(channel);
				if (index < 0)
					continue;
				Piece piece = torrent.registerPiece(index);
				channel.addMaximumRequests(piece, piece.getNotRequested());
				// Update partial_entries and possibly prevent next channels
				// from selecting a new piece, and avoid piling up pieces.
				partial_entries = torrent.getPartialPieces();
			}
		}
	}

	private void requestEndGameBlocks() {
		// On end-game, a block may be requested from different channels.

		if (torrent.isSeed() || torrent.hasUnregisteredPieces())
			return;

		Collection<Piece> partial_entries = torrent.getPartialPieces();
		for (PeerChannel channel : channels) {
			if (channel.amChoked() || !channel.amInterested())
				continue;
			for (Piece piece : partial_entries) {
				int index = piece.getIndex();
				if (!channel.hasPiece(index))
					continue;
				if (!channel.canRequestMore())
					break;
				BitSet blocks = channel.findNotRequested(piece);
				blocks.andNot(piece.getReservedBlocks());
				channel.addMaximumRequests(piece, blocks);
			}
			if (!channel.canRequestMore())
				continue;
			for (Piece piece : partial_entries) {
				if (channel.hasPiece(piece.getIndex()))
					piece.getReservedBlocks().clear();
			}
		}
	}

	private int selectRandomPiece(PeerChannel channel_interested) {
		BitSet unregistered = torrent.getUnregistered();
		BitSet available = channel_interested.getAvailablePieces();
		int index = -1;
		int nmatch = 0;
		int start_bit = available.nextSetBit(0);
		for (int i = start_bit; i >= 0; i = available.nextSetBit(i + 1)) {
			if (!unregistered.get(i))
				continue;
			if (Math.floor(Math.random() * ++nmatch) == 0)
				index = i;
		}
		return index;
	}

	private int selectRarePiece(PeerChannel channel_interested) {
		// The list of pieces is partitioned in 10 overlapped segments,
		// which are traversed in random order. From the first segment
		// with available pieces the least common piece is selected.
		// This algorithm does not select a piece that has the global
		// minimum availability, but a local one instead.

		Integer[] segments = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
		Collections.shuffle(Arrays.asList(segments));

		BitSet unregistered = torrent.getUnregistered();
		int pieces_length = unregistered.length();
		for (Integer seg : segments) {
			int min = Integer.MAX_VALUE;
			int index = -1;
			int nmatch = 0;
			for (int i = seg; i < pieces_length; i += 10) {
				if (!unregistered.get(i) || !channel_interested.hasPiece(i))
					continue;
				int availability = 0;
				for (PeerChannel channel : channels) {
					if (!channel.hasPiece(i))
						continue;
					if (++availability > min)
						break;
				}
				if (availability == 0 || availability > min)
					continue;
				// If the segment has n pieces with the same minimum value
				// we select one of them at random with probability 1/n.
				nmatch = availability == min ? nmatch + 1 : 0;
				if (availability < min
						|| Math.floor(Math.random() * nmatch) == 0) {
					min = availability;
					index = i;
				}
			}
			if (index >= 0)
				return index;
		}

		return -1;
	}

	private void keepConnectionsAlive() {
		long now = System.nanoTime();
		for (PeerChannel channel : channels) {
			// If the socket has an outgoing message for more than 60 seconds,
			// it probably has stalled. In this case we don't add a keep-alive
			// message.
			if (now - channel.socket.lastOutputMessageAt() > 60 * 1e9
					&& !channel.socket.hasOutputMessage()) {
				channel.addKeepAlive();
			}
		}
	}

	public void addStatusObserver(StatusObserver observer) {
		if (observer == null)
			throw new NullPointerException();

		observers.add(observer);
	}

	public void removeStatusObserver(StatusObserver observer) {
		observers.remove(observer);
	}

	private void notifyStatusObservers() {
		if (observers.isEmpty())
			return;

		List<ConnectionInfo> connections = new ArrayList<ConnectionInfo>();
		for (PeerChannel channel : channels) {
			connections.add(new ConnectionInfo(channel));
		}

		List<PieceInfo> pieces = new ArrayList<PieceInfo>();
		Collection<Piece> partial_entries = torrent.getPartialPieces();
		for (Piece piece : partial_entries) {
			pieces.add(new PieceInfo(piece));
		}

		List<TrackerInfo> trackers = new ArrayList<TrackerInfo>();
		for (Session session : sessions) {
			trackers.add(new TrackerInfo(session));
		}

		BitSet missing = new BitSet(torrent.numPieces());
		missing.set(0, torrent.numPieces());
		missing.andNot(torrent.getAvailablePieces());
		double input_rate = 0;
		double output_rate = 0;
		for (PeerChannel channel : channels) {
			input_rate += channel.socket.inputPerSec();
			output_rate += channel.socket.outputPerSec();
			missing.andNot(channel.getAvailablePieces());
		}

		// Estimated time of arrival
		long remaining = torrent.getRemainingLength();
		if (remaining == 0)
			eta = 0;
		else {
			// Once every 4s
			long now = System.nanoTime();
			if (now >= eta_timeout) {
				double rate = input_rate;
				eta = 1 + (rate > 0 ? (long) (0.5 + remaining / rate) : -1);
				eta_timeout = now + 4 * SECOND;
			}
		}
		if (eta >= 0)
			eta -= 1;

		TorrentInfo torrent_info = new TorrentInfo(torrent, missing, eta,
				input_rate, output_rate);

		String info_hash = meta.getInfoHash();
		for (StatusObserver o : observers) {
			o.asyncTorrentStatus(torrent_info, info_hash);
			o.asyncTrackers(trackers, info_hash);
			o.asyncPieces(pieces, info_hash);
			o.asyncConnections(connections, info_hash);
		}
	}
}
