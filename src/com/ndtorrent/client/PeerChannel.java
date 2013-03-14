package com.ndtorrent.client;

import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public final class PeerChannel implements Comparable<PeerChannel> {
	private static final int MIN_REQUESTS = 2;
	private static final int MAX_REQUESTS = 255;

	public BTSocket socket;

	private BitSet available = new BitSet();
	private BitSet have_advertised = new BitSet();
	private BitSet participated = new BitSet(); // received Piece indices

	public boolean is_initiator;
	public boolean is_banned;
	public boolean is_questionable; // participated in rejected pieces

	private boolean am_snubbed;
	private boolean is_optimistic;
	private boolean former_optimistic;

	private boolean am_choked = true;
	private boolean is_choked = true;
	private boolean am_interested;
	private boolean is_interested;

	// Reciprocation round
	private long unchoke_end_time;
	private RollingTotal blocks_total = new RollingTotal();

	private LinkedList<Message> incoming = new LinkedList<Message>();
	private LinkedList<Message> outgoing = new LinkedList<Message>();

	// Distinct list for Piece messages to reduce "iterate and filter" code.
	private LinkedList<Message> outgoing_pieces = new LinkedList<Message>();

	// Requests and pieces the client has received.
	private LinkedList<Message> unprocessed_pieces = new LinkedList<Message>();
	private LinkedList<Message> unprocessed_requests = new LinkedList<Message>();

	// Requests the client has sent.
	private LinkedList<Message> unfulfilled = new LinkedList<Message>();

	@Override
	public int compareTo(PeerChannel other) {
		// Blocks total comparison for descending order, c2 > c1
		return Long.signum(other.getBlocksTotal() - getBlocksTotal());
	}

	public int numAvailablePieces() {
		return available.cardinality();
	}

	public int numIncomingRequests() {
		return outgoing_pieces.size() + unprocessed_requests.size();
	}

	public int numOutgoingRequests() {
		return unfulfilled.size();
	}

	public void rollBlocksTotal(long current_time) {
		blocks_total.roll(current_time);
		blocks_total.add(socket.blocksInputTotal());
		socket.clearBlocksInputTotal();
	}

	public long getBlocksTotal() {
		return blocks_total.getTotal();
	}

	public void processIncomingMessages() {
		receiveIncoming();
		processIncoming();
	}

	public void processOutgoingMessages() {
		// Notification messages have higher priority and are sent ASAP,
		// because subsequent Pieces in a slow upload channel can block
		// peers from communicating.
		sendOutgoing(outgoing);
		sendOutgoing(outgoing_pieces);
	}

	public boolean hasOutgoingMessages() {
		return socket.hasOutputMessage() || hasReadyOutgoingPiece()
				|| !outgoing.isEmpty();
	}

	private boolean hasReadyOutgoingPiece() {
		for (Message m : outgoing_pieces) {
			if (m.isPrepared())
				return true;
		}
		return false;
	}

	public boolean hasUnprocessedIncoming() {
		return !unprocessed_pieces.isEmpty() || !unprocessed_requests.isEmpty();
	}

	public Message takeUnprocessedIncoming() {
		// Returns null if no message exists.
		if (!unprocessed_pieces.isEmpty())
			return unprocessed_pieces.pollFirst();
		else
			return unprocessed_requests.pollFirst();
	}

	public boolean participatedIn(int piece_index) {
		return participated.get(piece_index);
	}

	public boolean hasPiece(int index) {
		return available.get(index);
	}

	public boolean hasPieces(BitSet pieces) {
		BitSet common = (BitSet) available.clone();
		common.and(pieces);
		return common.equals(pieces);
	}

	public BitSet getAvailablePieces() {
		return available;
	}

	public boolean canRequestMore() {
		// A small number of pipelined requests, i.e. 10, on fast channels,
		// can result to bad download rates even on local connections!
		final int REQUESTS = 4;
		return numOutgoingRequests() < Math.max(MIN_REQUESTS,
				(Math.min(REQUESTS, MAX_REQUESTS)));
	}

	public void getRequested(BitSet requested, int piece_index, int block_length) {
		for (Message m : unfulfilled) {
			if (m.getPieceIndex() == piece_index) {
				requested.set(m.getBlockBegin() / block_length);
			}
		}
	}

	public void addOutgoingRequests(Piece piece) {
		BitSet bs = piece.getNotRequested();
		int start_bit = bs.nextSetBit(0);
		if (!canRequestMore() || start_bit < 0)
			return;
		for (int i = start_bit; i >= 0; i = bs.nextSetBit(i + 1)) {
			bs.flip(i);
			int index = piece.getIndex();
			int offset = piece.getBlockOffset(i);
			int length = piece.getBlockLength(i);
			Message m = Message.newBlockRequest(index, offset, length);
			outgoing.add(m);
			unfulfilled.add(m);
			if (!canRequestMore())
				return;
		}
	}

	public void advertiseBitfield(BitSet pieces, int nbits) {
		have_advertised = pieces;
		if (pieces.cardinality() > 0)
			outgoing.add(Message.newBitfield(pieces, nbits));
	}

	public void advertise(BitSet pieces) {
		have_advertised.xor(pieces);
		int start_bit = have_advertised.nextSetBit(0);
		for (int i = start_bit; i >= 0; i = have_advertised.nextSetBit(i + 1)) {
			outgoing.add(Message.newHavePiece(i));
		}
		have_advertised.or(pieces);
	}

	public void setAmSnubbed(boolean snubbed) {
		am_snubbed = snubbed;
	}

	public boolean amSnubbed() {
		// If true, it'll clear only as an optimistic unchoke.
		return am_snubbed;
	}

	public void setIsOptimistic(boolean optimistic) {
		is_optimistic = optimistic;
	}

	public boolean isOptimistic() {
		return is_optimistic;
	}

	public void setFormerOptimistic(boolean former) {
		former_optimistic = former;
	}

	public boolean isFormerOptimistic() {
		return former_optimistic;
	}

	public boolean amChoked() {
		return am_choked;
	}

	public boolean isChoked() {
		return is_choked;
	}

	public boolean amInterested() {
		return am_interested;
	}

	public boolean isInterested() {
		return is_interested;
	}

	public void setUnchokeEndTime(long at) {
		unchoke_end_time = at;
	}

	public long getUnchokeEndTime() {
		return unchoke_end_time;
	}

	public void updateIsChoked(boolean choke) {
		if (is_choked == choke)
			return;
		is_choked = choke;
		if (is_choked) {
			outgoing.add(Message.newChoke());
			outgoing_pieces.clear();
			unprocessed_requests.clear();
		} else {
			outgoing.add(Message.newUnchoke());
		}
	}

	public void updateAmInterested() {
		BitSet missing = (BitSet) available.clone();
		missing.andNot(have_advertised);
		boolean be_interested = missing.nextSetBit(0) >= 0;
		if (am_interested == be_interested)
			return;
		am_interested = be_interested;
		if (am_interested) {
			outgoing.add(Message.newInterested());
		} else {
			outgoing.add(Message.newNotInterested());
			removeOutgoingRequests();
		}
	}

	public void addPiece(Message m) {
		if (m == null)
			return;
		if (!m.isPiece())
			throw new IllegalArgumentException(m.getType());

		outgoing_pieces.add(m);
	}

	public void addKeepAlive() {
		outgoing.add(Message.newKeepAlive());
	}

	private void removeOutgoingPiece(Message m) {
		Iterator<Message> iter = outgoing_pieces.iterator();
		while (iter.hasNext()) {
			if (m.sameBlockRegion(iter.next())) {
				iter.remove();
				return;
			}
		}
	}

	private void removeOutgoingRequests() {
		unfulfilled.clear();
		Iterator<Message> iter = outgoing.iterator();
		while (iter.hasNext()) {
			if (iter.next().isBlockRequest())
				iter.remove();
		}
	}

	public void cancelPendingRequests(int piece_index) {
		Iterator<Message> iter;
		iter = unfulfilled.iterator();
		while (iter.hasNext()) {
			Message m = iter.next();
			if (m.getPieceIndex() == piece_index) {
				int offset = m.getBlockBegin();
				int length = m.getBlockLength();
				outgoing.add(Message.newCancel(piece_index, offset, length));
				iter.remove();
			}
		}
		iter = outgoing.iterator();
		while (iter.hasNext()) {
			Message m = iter.next();
			if (m.isBlockRequest() && m.getPieceIndex() == piece_index) {
				iter.remove();
			}
		}
	}

	private void removeUnprocessedRequest(Message m) {
		Iterator<Message> iter = unprocessed_requests.iterator();
		while (iter.hasNext()) {
			if (m.sameBlockRegion(iter.next())) {
				iter.remove();
				return;
			}
		}
	}

	private void receiveIncoming() {
		while (true) {
			socket.processInput();
			if (!socket.hasInputMessage())
				return;
			incoming.add(socket.takeInputMessage());
		}
	}

	private void sendOutgoing(List<Message> messages) {
		socket.processOutput();
		Iterator<Message> iter = messages.iterator();
		while (iter.hasNext()) {
			Message m = iter.next();
			if (!m.isPrepared())
				continue;
			if (socket.hasOutputMessage())
				return;
			// System.out.printf("sent %s, %d\n", m.getType(), m.getLength());
			socket.setOutputMessage(m);
			socket.processOutput();
			iter.remove();
		}
	}

	private void processIncoming() {
		while (!incoming.isEmpty()) {
			Message m = incoming.pollFirst();

			// System.out.printf("got %s, %d\n", m.getType(), m.getLength());

			if (m.isKeepAlive())
				continue;

			switch (m.getID()) {
			case Message.CHOKE:
				onChoke(m);
				break;
			case Message.UNCHOKE:
				onUnchoke(m);
				break;
			case Message.INTERESTED:
				onInterested(m);
				break;
			case Message.NOT_INTERESTED:
				onNotInterested(m);
				break;
			case Message.HAVE:
				onHave(m);
				break;
			case Message.BITFIELD:
				onBitfield(m);
				break;
			case Message.REQUEST:
				onRequest(m);
				break;
			case Message.PIECE:
				onPiece(m);
				break;
			case Message.CANCEL:
				onCancel(m);
				break;

			default:
				socket.close();
				return;
			}
		}
	}

	private void onChoke(Message m) {
		am_choked = true;
		removeOutgoingRequests();
	}

	private void onUnchoke(Message m) {
		am_choked = false;
	}

	private void onInterested(Message m) {
		is_interested = true;
	}

	private void onNotInterested(Message m) {
		is_interested = false;

		// Discard pending pieces because it might not get choked.
		outgoing_pieces.clear();
		unprocessed_requests.clear();
	}

	private void onHave(Message m) {
		available.set(m.getPieceIndex());
	}

	private void onBitfield(Message m) {
		// TODO if not isValidBitfield close the socket
		available = m.toBitSet();
	}

	private void onRequest(Message m) {
		if (!is_choked && numIncomingRequests() < MAX_REQUESTS)
			unprocessed_requests.add(m);
	}

	private void onPiece(Message m) {
		Iterator<Message> iter = unfulfilled.iterator();
		while (iter.hasNext()) {
			Message request = iter.next();
			if (m.sameBlockRegion(request)) {
				unprocessed_pieces.add(m);
				participated.set(m.getPieceIndex());
				iter.remove();
				return;
			}
		}
		// The corresponding unfulfilled request wasn't found.
		// We discard the piece but we don't attempt to close the socket.
		// A valid case this can happen is when the client just became not
		// interested in the peer and the piece had already been sent.
	}

	private void onCancel(Message m) {
		// The request is either already processed and the piece is enqueued,
		removeOutgoingPiece(m);
		// or unprocessed.
		removeUnprocessedRequest(m);
	}

}
