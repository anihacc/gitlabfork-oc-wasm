package ca.chead.ocwasm;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.AbstractFloat;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.DoublePrecisionFloat;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.SinglePrecisionFloat;
import co.nstant.in.cbor.model.Tag;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;
import li.cil.oc.api.machine.Value;

/**
 * Handles encoding and decoding CBOR data items.
 * <p>
 * When encoding Java objects as CBOR, the following rules apply:
 * <ul>
 * <li>{@code null} is encoded as a CBOR simple value null (major 7, value
 * 22).</li>
 * <li>A {@link Boolean} is encoded as a CBOR simple value false (major 7,
 * value 20) or true (major 7, value 21).</li>
 * <li>A {@link Byte}, {@link Short}, {@link Integer}, or {@link Long} is
 * encoded as a CBOR integer (major 0 or 1).</li>
 * <li>A {@link Float} is encoded as a CBOR IEEE 754 single-precision value
 * (major 7, value 26).</li>
 * <li>A {@link Double} is encoded as a CBOR IEEE 754 double-precision value
 * (major 7, value 27).</li>
 * <li>An OpenComputers opaque {@link Value} (such as an open file handle) is
 * encoded as a CBOR unsigned integer (major 0) encoding a freshly allocated
 * descriptor number, tagged with the Identifier tag (39).</li>
 * <li>A {@link Character} or {@link String} is encoded as a CBOR text string
 * (major 3).</li>
 * <li>An array of {@code byte} is encoded as a CBOR byte string (major
 * 2).</li>
 * <li>An array of any element type other than {@code byte}, or any object
 * implementing {@link Iterable}, is encoded as a CBOR array (major 4) in
 * definite-length form containing the elements encoded recursively according
 * to these rules.</li>
 * <li>Any object implementing {@link java.util.Map java.util.Map} or {@link
 * scala.collection.Map scala.collection.Map} is encoded as a CBOR map (major
 * 5) in definite-length form containing the keys and values encoded
 * recursively according to these rules.</li>
 * <li>Any other object cannot be encoded.</li>
 * </ul>
 * <p>
 * When decoding CBOR to Java objects, the following rules apply:
 * <ul>
 * <li>A data item tagged with the UUID tag (37) must be of type byte string of
 * length 16, and is converted to the {@link String} form of the represented
 * UUID.</li>
 * <li>A data item tagged with the Identifier tag (39) must be of type unsigned
 * integer (major 0) and is decoded as an OpenComputers opaque {@link Value}
 * (such as an open file handle) by interpreting it as a descriptor number.
 * (Deprecated: for backwards compatibility, a byte string encoding a UUID may
 * also be tagged with tag 39 instead of tag 37)</li>
 * <li>A data item tagged with the External Reference tag (32,769) must be a
 * three-element array; the reference resolves to either an array of {@code
 * byte} or a {@link String} whose contents are taken from an arbitrary region
 * of linear memory. The first element must be an unsigned integer whose value
 * is either 2 or 3 indicating the major type to decode. The second element
 * must be an unsigned integer whose value is interpreted as a <a
 * href="syscall/package-summary.html">pointer</a>. The third element must be
 * an unsigned integer, or a negative integer in the case of major type 3,
 * whose value is interpreted as a <a
 * href="syscall/package-summary.html">length</a> (negative values for major
 * type 3 cause a NUL-terminator search to occur, as per the rules for <a
 * href="syscall/package-summary.html">strings</a>).</li>
 * <li>Any other tag, or a tag in any other location, is invalid.</li>
 * <li>A CBOR integer (major 0 or 1) is decoded to an {@link Integer} if the
 * value fits within that data type’s range, otherwise to a {@link Long}.</li>
 * <li>A CBOR byte string (major 2) is decoded to an array of {@code
 * byte}.</li>
 * <li>A CBOR text string (major 3) is decoded to a {@link String}.</li>
 * <li>A CBOR array (major 4) is decoded to an array of {@link Object}
 * containing the elements decoded recursively according to these rules.</li>
 * <li>A CBOR map (major 5) is decoded to a {@link HashMap} containing the keys
 * and values recursively decoded according to these rules, with the additional
 * restriction that keys may only be data items that decode to {@link String},
 * {@link Integer}, {@link Long}, {@link Boolean}, {@link Float}, or {@link
 * Double}.</li>
 * <li>A CBOR false (major 7, value 20) is decoded to {@link
 * Boolean#FALSE}.</li>
 * <li>A CBOR true (major 7, value 21) is decoded to {@link Boolean#TRUE}.</li>
 * <li>A CBOR null (major 7, value 22) and undefined (major 7, value 23) are
 * decoded to {@code null}.</li>
 * <li>A CBOR half-precision (major 7, value 25) or single-precision (major 7,
 * value 26) IEEE 754 value is decoded to a {@link Float}.</li>
 * <li>A CBOR double-precision IEEE 754 value (major 7, value 27) is decoded to
 * a {@link Double}.</li>
 * <li>Any other data item is invalid.</li>
 * </ul>
 * <p>
 * {@link DescriptorTable} further details the handling of {@link Value}
 * objects and descriptors.
 */
public final class CBOR {
	/**
	 * The tag for a CBOR binary-encoded UUID.
	 */
	private static final long UUID_TAG = 37;

	/**
	 * The tag for a CBOR Identifier.
	 */
	private static final long IDENTIFIER_TAG = 39;

	/**
	 * The tag for a CBOR external reference.
	 */
	private static final long EXTERNAL_REFERENCE_TAG = 32769;

	/**
	 * Converts a Java object into a CBOR data item.
	 *
	 * @param object The object to convert, which must be, and whose nested
	 * items must all be, of the understood types.
	 * @param descriptorAllocator A descriptor allocator in which to allocate
	 * descriptors for any opaque values encountered.
	 * @return The CBOR encoding.
	 */
	public static byte[] toCBOR(final Object object, final DescriptorTable.Allocator descriptorAllocator) {
		try {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			toCBOR(object, descriptorAllocator, baos);
			return baos.toByteArray();
		} catch(final IOException exp) {
			throw new RuntimeException("Impossible I/O error occurred", exp.getCause());
		}
	}

	/**
	 * Converts a Java object into a CBOR data item and writes it to a stream.
	 *
	 * @param object The object to convert, which must be, and whose nested
	 * items must all be, of the understood types.
	 * @param descriptorAllocator A descriptor allocator in which to allocate
	 * descriptors for any opaque values encountered.
	 * @param stream The stream to write the bytes to.
	 * @throws IOException If writing to the stream fails.
	 */
	public static void toCBOR(final Object object, final DescriptorTable.Allocator descriptorAllocator, final OutputStream stream) throws IOException {
		try {
			final CborEncoder enc = new CborEncoder(stream);
			enc.encode(toDataItem(object, descriptorAllocator));
		} catch(final CborException exp) {
			if(exp.getCause() instanceof IOException) {
				throw (IOException) exp.getCause();
			} else {
				throw new RuntimeException("CBOR encoding error (this is an OC-Wasm bug)", exp);
			}
		}
	}

	/**
	 * Converts a Java object to a CBOR data item.
	 *
	 * @param object The object to convert.
	 * @param descriptorAlloc A descriptor table allocator to use to allocate
	 * descriptors for any opaque values encountered.
	 * @return The CBOR data item.
	 */
	private static DataItem toDataItem(final Object object, final DescriptorTable.Allocator descriptorAlloc) {
		if(object == null) {
			return SimpleValue.NULL;
		} else if(object instanceof Boolean) {
			return ((Boolean) object) ? SimpleValue.TRUE : SimpleValue.FALSE;
		} else if(object instanceof Byte || object instanceof Short || object instanceof Integer || object instanceof Long) {
			final long value = ((Number) object).longValue();
			return (value >= 0) ? new UnsignedInteger(value) : new NegativeInteger(value);
		} else if(object instanceof Float) {
			return new SinglePrecisionFloat((Float) object);
		} else if(object instanceof Double) {
			return new DoublePrecisionFloat((Double) object);
		} else if(object instanceof Value) {
			final int descriptor = descriptorAlloc.add((Value) object);
			final DataItem ret = new UnsignedInteger(descriptor);
			ret.setTag(new Tag(IDENTIFIER_TAG));
			return ret;
		} else if(object instanceof String) {
			return new UnicodeString((String) object);
		} else if(object instanceof Character) {
			return new UnicodeString(object.toString());
		} else if(object instanceof byte[]) {
			return new ByteString((byte[]) object);
		} else if(object.getClass().isArray()) {
			final int length = java.lang.reflect.Array.getLength(object);
			final Array ret = new Array();
			for(int i = 0; i != length; ++i) {
				ret.add(toDataItem(java.lang.reflect.Array.get(object, i), descriptorAlloc));
			}
			return ret;
		} else if(object instanceof Iterable) {
			final Array ret = new Array();
			for(final Object i : ((Iterable) object)) {
				ret.add(toDataItem(i, descriptorAlloc));
			}
			return ret;
		} else if(object instanceof java.util.Map) {
			final Map ret = new Map();
			for(final java.util.Map.Entry<?, ?> i : ((java.util.Map<?, ?>) object).entrySet()) {
				ret.put(toDataItem(i.getKey(), descriptorAlloc), toDataItem(i.getValue(), descriptorAlloc));
			}
			return ret;
		} else if(object instanceof scala.collection.Map) {
			final Map ret = new Map();
			@SuppressWarnings("unchecked")
			final scala.collection.Map<Object, Object> map = (scala.collection.Map<Object, Object>) object;
			final scala.collection.Iterator<scala.Tuple2<Object, Object>> iter = map.iterator();
			while(iter.hasNext()) {
				final scala.Tuple2<Object, Object> entry = iter.next();
				ret.put(toDataItem(entry._1, descriptorAlloc), toDataItem(entry._2, descriptorAlloc));
			}
			return ret;
		} else {
			throw new RuntimeException("Unable to CBOR-encode object of type " + object.getClass() + " (this is an OC-Wasm bug or limitation)");
		}
	}

	/**
	 * Converts a CBOR data item to a Java array.
	 *
	 * @param source The bytes to read from.
	 * @param descriptorTable The descriptor table to use to resolve references
	 * to opaque values.
	 * @param descriptorListener A listener which is invoked and passed every
	 * descriptor encountered during conversion.
	 * @param memory The linear memory from which to resolve external
	 * references, if external references should be resolved.
	 * @return The object.
	 * @throws CBORDecodeException If the data in {@code source} is not a valid
	 * CBOR data item (including if it is an external reference and {@code
	 * memory} is absent) or the item or one of its nested items is of an
	 * unsupported type, or if it is valid but is not an array.
	 * @throws BadDescriptorException If the data contains a reference to a
	 * descriptor, but the descriptor does not exist in the descriptor table.
	 * @throws MemoryFaultException If the data contains an external reference
	 * and the external reference pointer is null or the pointer and length
	 * point outside the memory area. This exception is impossible if {@code
	 * memory} is absent.
	 */
	public static Object[] toJavaArray(final ByteBuffer source, final DescriptorTable descriptorTable, final IntConsumer descriptorListener, final Optional<ByteBuffer> memory) throws CBORDecodeException, BadDescriptorException, MemoryFaultException {
		final Object ret = toJavaObject(source, descriptorTable, descriptorListener, memory);
		if(ret instanceof Object[]) {
			return (Object[]) ret;
		} else {
			throw new CBORDecodeException();
		}
	}

	/**
	 * Converts a CBOR data item to a Java object.
	 *
	 * @param source The bytes to read from.
	 * @param descriptorTable The descriptor table to use to resolve references
	 * to opaque values.
	 * @param descriptorListener A listener which is invoked and passed every
	 * descriptor encountered during conversion.
	 * @param memory The linear memory from which to resolve external
	 * references, if external references should be resolved.
	 * @return The object.
	 * @throws CBORDecodeException If the data in {@code source} is not a valid
	 * CBOR data item (including if it is an external reference and {@code
	 * memory} is absent) or the item or one of its nested items is of an
	 * unsupported type.
	 * @throws BadDescriptorException If the data contains a reference to a
	 * descriptor, but the descriptor does not exist in the descriptor table.
	 * @throws MemoryFaultException If the data contains an external reference
	 * and the external reference pointer is null or the pointer and length
	 * point outside the memory area. This exception is impossible if {@code
	 * memory} is absent.
	 */
	public static Object toJavaObject(final ByteBuffer source, final DescriptorTable descriptorTable, final IntConsumer descriptorListener, final Optional<ByteBuffer> memory) throws CBORDecodeException, BadDescriptorException, MemoryFaultException {
		Objects.requireNonNull(source);
		Objects.requireNonNull(descriptorTable);
		Objects.requireNonNull(descriptorListener);
		Objects.requireNonNull(memory);

		if(source.remaining() == 0) {
			return null;
		}

		final DataItem item;
		try {
			item = new CborDecoder(ByteBufferInputStream.wrap(source)).decodeNext();
		} catch(final CborException exp) {
			throw new CBORDecodeException();
		}
		return toJavaObject(item, descriptorTable, descriptorListener, memory);
	}

	/**
	 * Converts a CBOR data item into a Java object.
	 *
	 * @param item The item to convert.
	 * @param descriptorTable The descriptor table to use to resolve references
	 * to opaque values.
	 * @param descriptorListener A listener which is invoked and passed every
	 * descriptor encountered during conversion.
	 * @param memory The linear memory from which to resolve external
	 * references, if external references should be resolved.
	 * @return A Java object corresponding to the given CBOR data item.
	 * @throws CBORDecodeException If the item is not of a convertible type
	 * (including if it is an external reference and {@code memory} is absent).
	 * @throws BadDescriptorException If the data contains a reference to a
	 * descriptor, but the descriptor does not exist in the descriptor table.
	 * @throws MemoryFaultException If the data contains an external reference
	 * and the external reference pointer is null or the pointer and length
	 * point outside the memory area. This exception is impossible if {@code
	 * memory} is absent.
	 */
	private static Object toJavaObject(final DataItem item, final DescriptorTable descriptorTable, final IntConsumer descriptorListener, final Optional<ByteBuffer> memory) throws CBORDecodeException, BadDescriptorException, MemoryFaultException {
		final Tag tag = item.getTag();
		if(tag != null) {
			if(tag.getValue() == IDENTIFIER_TAG) {
				if(tag.hasTag()) {
					throw new CBORDecodeException();
				}
				if(item instanceof co.nstant.in.cbor.model.Number) {
					final int descriptor;
					try {
						descriptor = ((co.nstant.in.cbor.model.Number) item).getValue().intValueExact();
					} catch(final ArithmeticException exp) {
						throw new CBORDecodeException();
					}
					descriptorListener.accept(descriptor);
					try(ValueReference ref = descriptorTable.get(descriptor)) {
						// TODO keep the ref for longer.
						return ref.get();
					}
				} else if(item instanceof ByteString) {
					return toJavaUUID((ByteString) item);
				} else {
					throw new CBORDecodeException();
				}
			} else if(tag.getValue() == UUID_TAG) {
				if(tag.hasTag()) {
					throw new CBORDecodeException();
				}
				if(item instanceof ByteString) {
					return toJavaUUID((ByteString) item);
				} else {
					throw new CBORDecodeException();
				}
			} else if(tag.getValue() == EXTERNAL_REFERENCE_TAG) {
				if(tag.hasTag()) {
					throw new CBORDecodeException();
				}
				final ByteBuffer mem;
				try {
					mem = memory.get();
				} catch(final NoSuchElementException exp) {
					throw new CBORDecodeException();
				}
				if(item instanceof Array) {
					final int externalReferenceArrayLength = 3 /* major, pointer, length */;
					final List<DataItem> items = ((Array) item).getDataItems();
					if(items.size() != externalReferenceArrayLength) {
						throw new CBORDecodeException();
					}

					final DataItem majorItem = items.get(0);
					if(!(majorItem instanceof UnsignedInteger)) {
						throw new CBORDecodeException();
					}
					final int majorInt;
					try {
						majorInt = ((UnsignedInteger) majorItem).getValue().intValueExact();
					} catch(final ArithmeticException exp) {
						throw new CBORDecodeException();
					}
					// MajorType.ofByte requires the major type to be
					// left-shifted five places, so ensure that will not lose
					// any bits before doing so.
					final int majorTypeShiftBits = 5;
					if(((majorInt << majorTypeShiftBits) >>> majorTypeShiftBits) != majorInt) {
						throw new CBORDecodeException();
					}
					final MajorType major = MajorType.ofByte(majorInt << majorTypeShiftBits);

					final DataItem pointerItem = items.get(1);
					if(!(pointerItem instanceof UnsignedInteger)) {
						throw new CBORDecodeException();
					}
					final int pointer;
					try {
						pointer = ((UnsignedInteger) pointerItem).getValue().intValueExact();
					} catch(final ArithmeticException exp) {
						throw new MemoryFaultException();
					}

					final DataItem lengthItem = items.get(2);
					final int length;
					if(lengthItem instanceof UnsignedInteger) {
						// The length is nonnegative, so we will use it as the
						// effective length. If it is too large to fit in an
						// int, then it clearly exceeds the bounds of the
						// module’s linear memory, so that’s a type of memory
						// fault.
						try {
							length = ((UnsignedInteger) lengthItem).getValue().intValueExact();
						} catch(final ArithmeticException exp) {
							throw new MemoryFaultException();
						}
					} else if(lengthItem instanceof NegativeInteger) {
						// For a Unicode string, a negative length tells us to
						// do a NUL terminator search. For a byte string, a
						// negative length is invalid and will be caught by
						// MemoryUtils.region. We don’t actually care whether
						// the value fits in an int or not! For a Unicode
						// string, all negative integers behave the same (do a
						// NUL terminator search), and for a byte string, all
						// negative integers behave the same (fail). So rather
						// than trying to extract the value as an int and
						// dealing with too-large values, just use −1.
						length = -1;
					} else {
						throw new CBORDecodeException();
					}

					switch(major) {
						case BYTE_STRING:
							final ByteBuffer retBuffer = MemoryUtils.region(mem, pointer, length);
							final byte[] ret = new byte[retBuffer.remaining()];
							retBuffer.get(ret);
							return ret;

						case UNICODE_STRING:
							try {
								return WasmString.toJava(mem, pointer, length);
							} catch(final StringDecodeException exp) {
								throw new CBORDecodeException();
							}

						default:
							throw new CBORDecodeException();
					}
				} else {
					throw new CBORDecodeException();
				}
			} else {
				throw new CBORDecodeException();
			}
		} else if(item instanceof Array) {
			final List<DataItem> items = ((Array) item).getDataItems();
			final Object[] objects = new Object[items.size()];
			for(int i = 0; i != objects.length; ++i) {
				objects[i] = toJavaObject(items.get(i), descriptorTable, descriptorListener, memory);
			}
			return objects;
		} else if(item instanceof ByteString) {
			return ((ByteString) item).getBytes();
		} else if(item instanceof Map) {
			final Map src = (Map) item;
			final HashMap<Object, Object> dest = new HashMap<Object, Object>();
			final Iterator<DataItem> keyIter = src.getKeys().iterator();
			final Iterator<DataItem> valueIter = src.getValues().iterator();
			while(keyIter.hasNext()) {
				final Object key = toJavaObject(keyIter.next(), descriptorTable, descriptorListener, memory);
				if(key instanceof String || key instanceof java.lang.Number || key instanceof Boolean) {
					final Object value = toJavaObject(valueIter.next(), descriptorTable, descriptorListener, memory);
					dest.put(key, value);
				} else {
					// Map keys must only be strings, numbers, or booleans.
					throw new CBORDecodeException();
				}
			}
			return dest;
		} else if(item instanceof co.nstant.in.cbor.model.Number) {
			try {
				final long l = ((co.nstant.in.cbor.model.Number) item).getValue().longValueExact();
				final int i = (int) l;
				if(i == l) {
					// The value is small enough to fit in an int, so return it
					// that way.
					return i;
				} else {
					// The value is too big for an int, so return it as a long.
					return l;
				}
			} catch(final ArithmeticException exp) {
				throw new CBORDecodeException();
			}
		} else if(item instanceof AbstractFloat) {
			return ((AbstractFloat) item).getValue();
		} else if(item instanceof DoublePrecisionFloat) {
			return ((DoublePrecisionFloat) item).getValue();
		} else if(item instanceof SimpleValue) {
			switch(((SimpleValue) item).getSimpleValueType()) {
				case FALSE: return false;
				case TRUE: return true;
				case NULL: return null;
				case UNDEFINED: return null;
				default: throw new RuntimeException("Impossible simple CBOR value type " + ((SimpleValue) item).getSimpleValueType());
			}
		} else if(item instanceof UnicodeString) {
			return ((UnicodeString) item).getString();
		}
		throw new CBORDecodeException();
	}

	/**
	 * Converts a CBOR byte string into a UUID.
	 *
	 * @param item The item to convert.
	 * @return The decoded UUID, in string form.
	 * @throws CBORDecodeException If the item is the wrong length.
	 */
	private static String toJavaUUID(final ByteString item) throws CBORDecodeException {
		final ByteBuffer bb = ByteBuffer.wrap(item.getBytes());
		if(bb.remaining() == MemoryUtils.UUID_BYTES) {
			final long msw = bb.getLong();
			final long lsw = bb.getLong();
			return new UUID(msw, lsw).toString();
		} else {
			throw new CBORDecodeException();
		}
	}

	private CBOR() {
	}
}
