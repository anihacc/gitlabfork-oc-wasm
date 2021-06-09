package ca.chead.ocwasm;

import java.nio.ByteBuffer;

/**
 * The base class of all Wasm modules used in OC-Wasm.
 *
 * The following data types are used for transferring information between the
 * Wasm module and the host:
 * <ul>
 * <li>A <dfn>pointer</dfn> is an {@code i32} with nonnegative value. The value
 * zero is interpreted as a null pointer. Nonzero values are interpreted as
 * offsets within the module’s linear memory.</li>
 * <li>A <dfn>length</dfn> is an {@code i32} with nonnegative value. It
 * specifies the length of the data in bytes. For values passed from Wasm
 * module to host, if the length accompanies an optional pointer and the
 * pointer is null, then the length is ignored. For values passed from host to
 * Wasm module as return values, negative numbers typically encode errors.</li>
 * <li>A <dfn>string</dfn> is a contiguous region of memory containing a UTF-8
 * sequence. The length of the string is indicated out-of-band, typically as an
 * additional parameter or a return value; no NUL or other terminator is used.
 * For values passed from host to Wasm module, the API call will write the
 * string into a module-provided buffer, then indicate the number of bytes
 * written. For values passed from Wasm module to host, as a convenience to
 * languages where NUL terminators are commonly used, the module may instead
 * elect to pass a negative number as the length, in which case the host will
 * search for a NUL terminator to determine string length.</li>
 * <li>A <dfn>CBOR object</dfn> is a contiguous region of memory containing a
 * Concise Binary Object Representation encoding of a single Data Item.
 * Individual occurrences will generally further constrain the type of data
 * item (e.g. “CBOR map”, “CBOR array”, etc.). CBOR objects may or may not be
 * accompanied by length information, as CBOR is self-delimiting. The host will
 * never write a CBOR data item using indefinite-length encoding (using a break
 * marker), but it will accept indefinite-length-encoded values from the Wasm
 * module.</li>
 * <li>A <dfn>CBOR sequence</dfn> is a contiguous region of memory containing
 * zero or more CBOR-encoded Data Items positioned immediately following one
 * another. The length of the encoded data is indicated out-of-band, typically
 * as an additional parameter or return value.</li>
 * </ul>
 *
 * Unless otherwise specified, all syscalls that involve memory access that
 * memory only during the syscall; once the syscall returns, the host will not
 * access the memory and the module can reuse it or free it.
 */
public abstract class ModuleBase {
	/**
	 * The number of bytes per unit of {@link #freeMemory}.
	 */
	public static final int FREE_MEMORY_UNIT = 4;

	/**
	 * Whether or not the current timeslice has timed out and execution should
	 * be aborted.
	 *
	 * This variable is written by two threads: it is set to {@code false} in
	 * {@link ca.chead.ocwasm.state.Run#runThreaded} on the computer thread,
	 * then potentially set to {@code true} in the timeout future on the
	 * background executor thread. See the comment in that method for how
	 * proper ordering of writes is guaranteed, to avoid races.
	 *
	 * During module execution, this variable is potentially set to {@code
	 * true} in the timeout future on the background executor thread while
	 * being read in the generated bytecode on the computer thread. It is
	 * {@code volatile} to ensure that the write will be observed promptly.
	 */
	// Making this into a method would be bad. As a field, it does not affect
	// subclasses at all. As a method, it would either be final (which would
	// arbitrarily restrict subclasses from having a method with the same name
	// and parameters) or nonfinal (which would allow subclasses to override
	// it).
	@SuppressWarnings("checkstyle:VisibilityModifier")
	public volatile boolean timedOut;

	/**
	 * The current amount of RAM that is free, in {@link
	 * #FREE_MEMORY_UNIT}-byte units.
	 *
	 * This variable is only touched by the computer thread.
	 */
	// Making this into a method would be bad. As a field, it does not affect
	// subclasses at all. As a method, it would either be final (which would
	// arbitrarily restrict subclasses from having a method with the same name
	// and parameters) or nonfinal (which would allow subclasses to override
	// it).
	@SuppressWarnings("checkstyle:VisibilityModifier")
	public int freeMemory;

	/**
	 * Runs the module.
	 *
	 * Normally this is called once at startup and then again each time the
	 * previous call’s requested sleep time completes. However, it may be
	 * called earlier than the requested sleep time under three conditions:
	 * <ul>
	 * <li>If an indirect method call is started, this will be called again
	 * immediately after the method call completes, with no other intervening
	 * calls.</li>
	 * <li>If a signal is received during the sleep, or is present when the
	 * previous invocation returns, this will be called immediately,
	 * interrupting the requested sleep.</li>
	 * <li>Potentially, if the computer is saved and reloaded (either due to
	 * the server shutting down or due to the chunk being unloaded) during the
	 * sleep.</li>
	 * </ul>
	 *
	 * Therefore, a module that actually needs to sleep for a specific amount
	 * of time should use either {@link
	 * ca.chead.ocwasm.syscall.Computer#uptime} or {@link
	 * ca.chead.ocwasm.syscall.Computer#worldTime} to calculate sleep periods,
	 * depending on how it wishes to handle players using beds to skip time.
	 *
	 * @param callCompleted {@code true} if a method call was previously
	 * started but not finished and has now finished.
	 * @return The number of ticks to sleep before the next call.
	 * @throws ExecutionException If a stack overflow occurs, the computer’s
	 * timeslice expires, or the module requests to shut down the computer.
	 * @throws WrappedException If a syscall invoked by the Wasm module
	 * instance failed for a reason that was not the module instance’s fault.
	 */
	public abstract int run(int callCompleted) throws ExecutionException, WrappedException;

	/**
	 * Writes the contents of all mutable globals to a {@link ByteBuffer}.
	 *
	 * This method is automatically implemented by the compilation process. It
	 * must not be implemented in the Wasm module itself.
	 *
	 * @param buffer The ByteBuffer into which to write the mutable globals.
	 */
	public abstract void saveMutableGlobals(ByteBuffer buffer);

	/**
	 * Constructs a new {@code ModuleBase}.
	 *
	 * @param listener The listener that wishes to be notified of the module’s
	 * partial construction.
	 */
	protected ModuleBase(final ModuleConstructionListener listener) {
		super();
		timedOut = false;
		freeMemory = 0;
		listener.instanceConstructed(this);
	}
}
