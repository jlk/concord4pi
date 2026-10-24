package concord4pi.SB2000;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

import com.fazecast.jSerialComm.*;

import concord4pi.logging.LogEngine;

import org.tinylog.*;

public class SB2000Controller implements Runnable {
	private Queue<IBaseMessage> txQueue = new ConcurrentLinkedQueue<IBaseMessage>();
	private Queue<IBaseMessage> rxQueue = new ConcurrentLinkedQueue<IBaseMessage>();
	private Queue<IBaseMessage> txVerifyQueue = new ConcurrentLinkedQueue<IBaseMessage>();
	
	private final int maxSimultaneousThreads = 1;
	Semaphore lock = new Semaphore(maxSimultaneousThreads);
	//the receive buffer is appended to by the serial listener and scanned in place by the
	//run loop, so neither side copies the whole thing around.  Guarded by lock.
	private final StringBuilder dataToProcess = new StringBuilder();
	private boolean newDataWaiting = false;
	private static final String MESSAGE_START_TEXT = String.valueOf((char)SB2000Constants.MESSAGE_START);
	
	private SerialController serialPort;
	private LogEngine logger;
	private boolean isRunning = false;
	
	//the run loop waits on this instead of polling.  Anything that gives the loop work to
	//do signals it; the timeout is a fallback so time-driven work and the isRunning check
	//still happen on a quiet line.
	private static final long IDLE_WAIT_MS = 500;
	private final Object workSignal = new Object();
	private boolean workPending = false;	//guarded by workSignal
	
	public SB2000Controller(String serialDeviceName, LogEngine logger) {
		this(new SerialController(serialDeviceName, logger), logger);
	}

	//package-private so tests can supply a SerialController that doesn't open a port
	SB2000Controller(SerialController serialPort, LogEngine logger) {
		this.logger = logger;
		isRunning = true;
		this.serialPort = serialPort;
		setupCallbacks();
	}
	
	public void setupCallbacks() {
		serialPort.addDataListener(new serialDataListener());
	}
	
	public void run() {
		while(isRunning) {
			//process all the queues
			//first check to see if there is anything on the transmit queue
			processOutgoingMessages();
			
			//then process anything that is incoming
			processIncomingMessages();

			awaitWork();
		}
	}
	
	//blocks until there's something to do, or IDLE_WAIT_MS passes.  Returns immediately if
	//the last pass queued anything to transmit (an ACK, a NAK, a retransmit), so those go
	//out on the next pass rather than waiting for the timeout.
	private void awaitWork() {
		synchronized(workSignal) {
			if(!workPending && txQueue.isEmpty()) {
				try {
					workSignal.wait(IDLE_WAIT_MS);
				} catch (InterruptedException interrupted) {
					//An interrupt means stop, not spin.  Re-arming the flag and looping would make
					//every later wait() throw immediately, turning the run loop into a busy wait.
					//Say so: this ends serial processing, so it shouldn't happen quietly.
					logger.log("Serial run loop interrupted... stopping", Level.WARN);
					Thread.currentThread().interrupt();
					isRunning = false;
				}
			}
			workPending = false;
		}
	}
	
	//wakes the run loop
	private void signalWork() {
		synchronized(workSignal) {
			workPending = true;
			workSignal.notifyAll();
		}
	}
	
	public boolean messageWaiting() {
		return !rxQueue.isEmpty();
	}
	
	public IOMessage getNextMessage() {
		return (IOMessage)rxQueue.poll();
	}
	
	public void sendMessage(IOMessage newMessage) {
		logger.log(() -> "Adding message [" + newMessage + "] to TXQueue", Level.TRACE);
		txQueue.offer(newMessage);
		signalWork();
	}
	
	public void sendMessages(IOMessage[] newMessages) {
		for(IOMessage newMessage: newMessages) {
			sendMessage(newMessage);
		}
	}
	
	public void shutdown() {
		isRunning = false;
		signalWork();	//so the run loop sees isRunning without waiting out the timeout
		serialPort.removeListener();
		serialPort.closePort();
	}
	
	void processOutgoingMessages() {
		IBaseMessage message;
		while(!txQueue.isEmpty()) {
			//send the messages in the transmit queue
			message = txQueue.poll();
			if(message.isControlMessage()) {
				serialPort.writeByte(((ControlMessage)message).message());
			}
			else {
				serialPort.writeBytes(((IOMessage)message).toString());
				txVerifyQueue.offer(message);
			}			
		}
	}
	
	void processIncomingMessages() {
		//exit for zero length arrays
		if((dataToProcess.length() == 0) || !newDataWaiting) { return; }
		
		try {
			//use semaphores so that we don't write and read at the same time
			lock.acquire();
			logger.log("Acquired lock for data processing", Level.TRACE);
			newDataWaiting = false;
			
			try {
				//scan the buffer in place.  cursor is how far into it we've got; nothing is
				//copied out of it except the frames handed to IOMessage.
				int cursor = 0;
			
				while(cursor < dataToProcess.length()) {
					//search through the characters to find messages and control characters;
					byte testChar = (byte)dataToProcess.charAt(cursor);
					if(testChar == SB2000Constants.ACK) {
						//manage the ACK
						//step past this character
						cursor++;

						//since I got an ACK... just throw the message away and dispose of the item on the verify queue, we're good.
						logger.log(() -> "Received ACK for message: [" + (IOMessage)txVerifyQueue.peek() + "]", Level.TRACE);
						txVerifyQueue.poll();

					}
					else if(testChar == SB2000Constants.NAK) {
						//manage the NAK
						//step past this character
						cursor++;

						//since it is a NAK, I need to re-transmit
						IOMessage theMessage = (IOMessage)txVerifyQueue.poll();
						if(theMessage != null) {
							if(theMessage.isValid()) {
								logger.log(() -> "Re-transmitting Message due to NAK: [" + theMessage + "]", Level.DEBUG);
								theMessage.incrementTransmissionCount();
								if(theMessage.isReTransmittable()) {
									txQueue.offer(theMessage);
								}
								else {
									logger.log("Maximum number of retransmissions reached: " + theMessage.getSummary(), Level.WARN);
								}
							}
							else {
								logger.log(() -> "Discarding Invalid Message queued for re-transmit: [" + theMessage + "]", Level.DEBUG);
							}
						}
						else {
							logger.log("Received a NAK, but no messages to retransmit!", Level.DEBUG);
						}				
					}
					else if(testChar == SB2000Constants.MESSAGE_START) {
						//step past this character
						cursor++;
						int remaining = dataToProcess.length() - cursor;

						if(remaining > 2) {
							//there is enough room to check for a message length
							//add one to include the checksum byte
							int length = 0;
							try {
								//Try to parse the message.  Ignore and discard it if exception occurs
								length = Integer.parseInt(dataToProcess, cursor, cursor + 2, 16) + 1;
							}
							catch (Exception e) {
								logger.log("Could not determine message length from message.  This shouldn't be the case.  Ignoring message... ", Level.WARN);
								length = -1;
							}
						
							//check if there is a full message according to the expected length
							//multiply by 2 since each number is represented by two ASCII characters
							if((length > 0) && (remaining >= (length * 2))) {
								String newMessageText = dataToProcess.substring(cursor, cursor + (length * 2)).trim();
								logger.log(() -> "Discovered new message [" + newMessageText + "]", Level.DEBUG);
							
								IOMessage newMessage = new IOMessage(newMessageText.getBytes());
							
								if(newMessage.isValid()) {
									logger.log(() -> "Received valid message [" + newMessageText + "]", Level.DEBUG);
									rxQueue.offer(newMessage);
									txQueue.offer(new ControlMessage(SB2000Constants.ACK));
								
								}
								else {
									logger.log(() -> "Received invalid message [" + newMessageText + "]", Level.DEBUG	);
									txQueue.offer(new ControlMessage(SB2000Constants.NAK));
								}
							
								//step past the message I just pulled out
								cursor += (length * 2);
							}
							else {
								//let's see if there is another MESSAGE START in the message
								int nextStart = dataToProcess.indexOf(MESSAGE_START_TEXT, cursor);
								if(nextStart >= 0) {
									//there is another message in here... the first one is probably corrupted
									//let's just go with it and discard the first message by invalidating it
									String newMessageText = dataToProcess.substring(cursor, nextStart).trim();
									logger.log(() -> "Discovered new message [" + newMessageText + "]", Level.DEBUG);
								
									IOMessage newMessage = new IOMessage(newMessageText.getBytes());
								
									if(newMessage.isValid()) {
										rxQueue.offer(newMessage);
										txQueue.offer(new ControlMessage(SB2000Constants.ACK));
										logger.log(() -> "Received valid message [" + newMessageText + "]", Level.DEBUG);
									}
									else {
										txQueue.offer(new ControlMessage(SB2000Constants.NAK));
										logger.log(() -> "Received invalid message [" + newMessageText + "]", Level.DEBUG	);
									}								
									cursor = nextStart;
								}
								else {
									requeueFrom(cursor);
									logger.log(() -> "Not enough data to process message [" + dataToProcess.substring(1) + "]... requeuing...", Level.TRACE);
									return;
								}
							}

						}
						else {
							requeueFrom(cursor);
							logger.log(() -> "Not enough data to determine message length [" + dataToProcess.substring(1) + "]... requeuing...", Level.TRACE);
							return;
						}
					}
					else {
						//couldn't find any control characters in the string.  I'll discard this text and wait for the next message to come in
						requeueFrom(cursor);
						logger.log(() -> "MESSAGE START code not found [" + dataToProcess.substring(1) + "]", Level.TRACE);
						return;
					}
				}
			
				//the whole buffer was framed
				dataToProcess.setLength(0);
			}
			catch(RuntimeException scanFailure) {
				//Framing a message can throw (see the zero-length frame under "Known defects").
				//Drop the buffer before rethrowing: it's what snapshotting it used to do, and
				//without it the bytes that threw stay at the head and every later pass rethrows
				//on them, so the framer never makes progress again.
				dataToProcess.setLength(0);
				throw scanFailure;
			}
		}
		catch(InterruptedException lockException) {
			logger.log("Could not acquire lock for processing data", Level.WARN);
		}
		finally {
			lock.release();
			logger.log("Release lock for data processing", Level.TRACE);
		}
	}
	
	//Drops what's already been framed and puts a MESSAGE_START back in front of the rest, so
	//the next pass re-frames from there.  Note it prepends MESSAGE_START even when the
	//remainder didn't start with one - see "SB2000Controller framing" under the known defects.
	private void requeueFrom(int cursor) {
		dataToProcess.delete(0, cursor);
		dataToProcess.insert(0, MESSAGE_START_TEXT);
	}
	
	class serialDataListener implements SerialPortDataListener {
		@Override
		public int getListeningEvents() {
			return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
		}

		@Override
		public void serialEvent(SerialPortEvent event) {
			//if this isn't the correct event, just leave the method
			if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_RECEIVED) {
				return;
			}

			appendReceivedData(event.getReceivedData());
		}
	}

	//package-private so tests can feed serial data without a jSerialComm event
	void appendReceivedData(byte[] receivedData) {
		try {
			//use semaphores, so we aren't reading and writing to the string
			//at the same time.
			lock.acquire();
			logger.log("Acquired lock for data processing", Level.TRACE);
			dataToProcess.append(new String(receivedData));
			newDataWaiting = true;
		}
		catch(InterruptedException lockException) {
			logger.log("Could not acquire lock for processing data", Level.WARN);
		}
		finally {
			lock.release();
			logger.log("Release lock for data processing", Level.TRACE);
		}
		
		signalWork();
	}
}
