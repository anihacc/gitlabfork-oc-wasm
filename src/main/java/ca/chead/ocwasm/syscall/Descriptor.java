package ca.chead.ocwasm.syscall;

import ca.chead.ocwasm.DescriptorTable;
import ca.chead.ocwasm.ErrorCode;
import ca.chead.ocwasm.ValueReference;
import ca.chead.ocwasm.WrappedException;
import java.util.Objects;

/**
 * The syscalls available for import into a Wasm module in the {@code
 * descriptor} component.
 *
 * See {@link DescriptorTable} for conceptual information about descriptors.
 */
public final class Descriptor {
	/**
	 * The descriptors and the values they refer to.
	 */
	private final DescriptorTable descriptors;

	/**
	 * Constructs a new {@code Descriptor}.
	 *
	 * @param descriptors The descriptor table for opaque values.
	 */
	public Descriptor(final DescriptorTable descriptors) {
		super();
		this.descriptors = Objects.requireNonNull(descriptors);
	}

	/**
	 * Closes a descriptor.
	 *
	 * If this was the last reference to a particular opaque value, the opaque
	 * value is disposed.
	 *
	 * @param descriptor The descriptor number to close.
	 * @return Zero on success, or {@link ErrorCode#BAD_DESCRIPTOR} if the
	 * descriptor does not exist.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int close(final int descriptor) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			descriptors.close(descriptor);
			return 0;
		});
	}

	/**
	 * Duplicates a descriptor.
	 *
	 * The new descriptor refers to the exact same opaque value, not a copy of
	 * the value. All references to either descriptor behave in exactly the
	 * same way, but each descriptor can be closed independently. Only once all
	 * descriptors referring to the opaque value are closed will the value
	 * itself be disposed.
	 *
	 * @param descriptor The descriptor number to duplicate.
	 * @return The new descriptor number on success, {@link
	 * ErrorCode#BAD_DESCRIPTOR} if the descriptor does not exist, or {@link
	 * ErrorCode#TOO_MANY_DESCRIPTORS} if the descriptor table is full.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int dup(final int descriptor) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			try(ValueReference value = descriptors.get(descriptor)) {
				if(descriptors.overfull()) {
					return ErrorCode.TOO_MANY_DESCRIPTORS.asNegative();
				}
				try(DescriptorTable.Allocator alloc = descriptors.new Allocator()) {
					final int newDescriptor = alloc.add(value.get());
					alloc.commit();
					return newDescriptor;
				}
			}
		});
	}
}
