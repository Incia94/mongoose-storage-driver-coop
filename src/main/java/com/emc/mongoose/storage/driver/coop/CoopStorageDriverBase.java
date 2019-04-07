package com.emc.mongoose.storage.driver.coop;

import static com.emc.mongoose.base.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.base.Constants.KEY_STEP_ID;
import static com.emc.mongoose.base.item.op.Operation.Status.ACTIVE;
import com.emc.mongoose.base.concurrent.ServiceTaskExecutor;
import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.config.IllegalConfigurationException;
import com.emc.mongoose.base.item.Item;
import com.emc.mongoose.base.item.op.Operation;
import com.emc.mongoose.base.item.op.composite.CompositeOperation;
import com.emc.mongoose.base.item.op.partial.PartialOperation;
import com.emc.mongoose.base.logging.Loggers;
import com.emc.mongoose.base.storage.driver.StorageDriver;
import com.emc.mongoose.base.storage.driver.StorageDriverBase;

import static com.github.akurilov.commons.lang.Exceptions.throwUnchecked;
import com.github.akurilov.confuse.Config;

import org.apache.logging.log4j.CloseableThreadContext;

import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

public abstract class CoopStorageDriverBase<I extends Item, O extends Operation<I>>
				extends StorageDriverBase<I, O> implements StorageDriver<I, O> {

	static int compareOps(final Operation op1, final Operation op2) {
		return op2.status().ordinal() - op1.status().ordinal();
	}

	protected final Semaphore concurrencyThrottle;
	private final BlockingQueue<O> incomingOpsQueue;
	private final LongAdder scheduledOpCount = new LongAdder();
	private final LongAdder completedOpCount = new LongAdder();
	protected final OperationDispatchTask incomingOpsDispatchTask;

	protected CoopStorageDriverBase(
					final String testStepId,
					final DataInput dataInput,
					final Config storageConfig,
					final boolean verifyFlag,
					final int batchSize)
					throws IllegalConfigurationException {
		super(testStepId, dataInput, storageConfig, verifyFlag);
		final var inQueueLimit = storageConfig.intVal("driver-limit-queue-input");
		this.incomingOpsQueue = new BoundedPriorityBlockngQueue<>(inQueueLimit, CoopStorageDriverBase::compareOps);
		this.incomingOpsDispatchTask = new OperationDispatchTask<>(
			ServiceTaskExecutor.INSTANCE, this, incomingOpsQueue, stepId, batchSize
		);
		this.concurrencyThrottle = new Semaphore(concurrencyLimit > 0 ? concurrencyLimit : Integer.MAX_VALUE, true);
	}

	@Override
	protected void doStart() throws IllegalStateException {
		incomingOpsDispatchTask.start();
	}

	@Override
	public final boolean put(final O op)  {
		if (!isStarted()) {
			throwUnchecked(new EOFException());
		}
		if (prepare(op) && incomingOpsQueue.offer(op)) {
			scheduledOpCount.increment();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public final int put(final List<O> ops, final int from, final int to) {
		if (!isStarted()) {
			throwUnchecked(new EOFException());
		}
		var i = from;
		O nextOp;
		while (i < to && isStarted()) {
			nextOp = ops.get(i);
			if (prepare(nextOp) && incomingOpsQueue.offer(ops.get(i))) {
				i++;
			} else {
				break;
			}
		}
		final var n = i - from;
		scheduledOpCount.add(n);
		return n;
	}

	@Override
	public final int put(final List<O> ops) {
		if (!isStarted()) {
			throwUnchecked(new EOFException());
		}
		var n = 0;
		for (final var nextOp : ops) {
			if (isStarted()) {
				if (prepare(nextOp) && incomingOpsQueue.offer(nextOp)) {
					n++;
				} else {
					break;
				}
			} else {
				break;
			}
		}
		scheduledOpCount.add(n);
		return n;
	}

	@Override
	public final int activeOpCount() {
		if (concurrencyLimit > 0) {
			return concurrencyLimit - concurrencyThrottle.availablePermits();
		} else {
			return Integer.MAX_VALUE - concurrencyThrottle.availablePermits();
		}
	}

	@Override
	public final long scheduledOpCount() {
		return scheduledOpCount.sum();
	}

	@Override
	public final long completedOpCount() {
		return completedOpCount.sum();
	}

	@Override
	public final boolean isIdle() {
		if (concurrencyLimit > 0) {
			return !concurrencyThrottle.hasQueuedThreads()
							&& concurrencyThrottle.availablePermits() >= concurrencyLimit;
		} else {
			return concurrencyThrottle.availablePermits() == Integer.MAX_VALUE;
		}
	}

	protected abstract boolean submit(final O op) throws IllegalStateException;

	protected abstract int submit(final List<O> ops, final int from, final int to)
					throws IllegalStateException;

	protected abstract int submit(final List<O> ops)
					throws IllegalStateException;

	@SuppressWarnings("unchecked")
	protected final boolean handleCompleted(final O op) {
		if(ACTIVE.equals(op.status())) {
			if (incomingOpsQueue.offer(op)) {
				return true;
			} else {
				Loggers.ERR.warn("{}: Child operations queue overflow, dropping the operation", toString());
				return false;
			}
		} else if (super.handleCompleted(op)) {
			completedOpCount.increment();
			if (op instanceof CompositeOperation) {
				final var parentOp = (CompositeOperation) op;
				if (!parentOp.allSubOperationsDone()) {
					final List<O> subOps = parentOp.subOperations();
					for (final O nextSubOp : subOps) {
						nextSubOp.status(ACTIVE);
						if (! incomingOpsQueue.offer(nextSubOp)) {
							Loggers.ERR.warn("{}: Child operations queue overflow, dropping the operation", toString());
							return false;
						}
					}
				}
			} else if (op instanceof PartialOperation) {
				final var subOp = (PartialOperation) op;
				final var parentOp = subOp.parent();
				if (parentOp.allSubOperationsDone()) {
					// execute once again to finalize the things if necessary:
					// complete the multipart upload, for example
					parentOp.status(ACTIVE);
					if (! incomingOpsQueue.offer((O) parentOp)) {
						Loggers.ERR.warn("{}: Child operations queue overflow, dropping the operation", toString());
						return false;
					}
				}
			}
			return true;
		} else {
			return false;
		}
	}

	@Override
	protected void doShutdown() {
		incomingOpsDispatchTask.stop();
		Loggers.MSG.debug("{}: shut down", toString());
	}

	@Override
	public boolean await(final long timeout, final TimeUnit timeUnit) throws InterruptedException {
		return false;
	}

	@Override
	protected void doClose() throws IOException, IllegalStateException {
		try (final var logCtx = CloseableThreadContext.put(KEY_STEP_ID, stepId)
						.put(KEY_CLASS_NAME, StorageDriverBase.class.getSimpleName())) {
			super.doClose();
			incomingOpsDispatchTask.close();
			incomingOpsQueue.clear();
		}
	}
}
