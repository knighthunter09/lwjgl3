/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.openal;

import org.lwjgl.system.*;

import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.system.APIUtil.*;
import static org.lwjgl.system.Checks.*;
import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * This class must be used before any OpenAL function is called. It has the following responsibilities:
 * <ul>
 * <li>Loads the OpenAL native library into the JVM process.</li>
 * <li>Creates instances of {@link ALCCapabilities} classes. An {@code ALCCapabilities} instance contains flags for functionality that is available for an
 * OpenAL device. Internally, it also contains function pointers that are only valid for that specific OpenAL device.</li>
 * </ul>
 *
 * <h3>Library lifecycle</h3>
 * <p>The OpenAL library is loaded automatically when this class is initialized. Set the {@link Configuration#EXPLICIT_INIT_OPENAL} option to override this
 * behavior. Manual loading/unloading can be achieved with the {@link #create} and {@link #destroy} functions. The name of the library loaded can be overridden
 * with the {@link Configuration#LIBRARY_NAME_OPENAL} option.</p>
 *
 * <h3>ALCCapabilities creation</h3>
 * <p>Instances of {@code ALCCapabilities} can be created with the {@link #createCapabilities} method. Calling this method is expensive, so
 * {@code ALCCapabilities} instances are automatically created by and cached in {@link ALDevice} instances.</p>
 *
 * @see ALContext
 * @see ALDevice
 * @see AL
 */
public final class ALC {

	private static FunctionProviderLocal functionProvider;

	private static ALCCapabilities libraryCapabilities;

	static {
		if ( !Configuration.EXPLICIT_INIT_OPENAL.<Boolean>get() )
			create();
	}

	private ALC() {}

	/** Loads the OpenAL native library, using the default library name. */
	public static void create() {
		String libName;
		switch ( Platform.get() ) {
			case LINUX:
			case MACOSX:
				libName = "openal";
				break;
			case WINDOWS:
				libName = "OpenAL";
				break;
			default:
				throw new IllegalStateException();
		}

		create(Configuration.LIBRARY_NAME_OPENAL.get(Pointer.BITS64 ? libName : libName + "32"));
	}

	/**
	 * Loads the OpenAL native library, using the specified library name.
	 *
	 * @param libName the native library name
	 */
	public static void create(String libName) {
		final SharedLibrary OPENAL = Library.loadNative(libName);

		try {
			FunctionProviderLocal functionProvider = new FunctionProviderLocal() {

				private final long alcGetProcAddress = getFunctionAddress("alcGetProcAddress");

				{
					if ( alcGetProcAddress == NULL ) {
						OPENAL.free();
						throw new RuntimeException("A core ALC function is missing. Make sure that the OpenAL library has been loaded correctly.");
					}
				}

				@Override
				public long getFunctionAddress(CharSequence functionName) {
					long address = OPENAL.getFunctionAddress(functionName);
					if ( address == NULL )
						apiLog("Failed to locate address for ALC function " + functionName);

					return address;
				}

				@Override
				public long getFunctionAddress(long handle, CharSequence functionName) {
					APIBuffer __buffer = apiBuffer();
					__buffer.stringParamASCII(functionName, true);

					long address = invokePPP(alcGetProcAddress, handle, __buffer.address());
					if ( address == NULL && handle != NULL )
						apiLog("Failed to locate address for ALC extension function " + functionName);

					return address;
				}

				@Override
				public void free() {
					OPENAL.free();
				}
			};
			create(functionProvider);
		} catch (RuntimeException e) {
			OPENAL.free();
			throw e;
		}
	}

	/**
	 * Initializes ALC with the specified {@link FunctionProviderLocal}. This method can be used to implement custom ALC library loading.
	 *
	 * @param functionProvider the provider of ALC function addresses
	 */
	public static void create(FunctionProviderLocal functionProvider) {
		if ( ALC.functionProvider != null )
			throw new IllegalStateException("ALC has already been created.");

		ALC.functionProvider = functionProvider;

		libraryCapabilities = new ALCCapabilities(functionProvider, NULL, new HashSet<String>(0));

		AL.init();
	}

	/** Unloads the OpenAL native library. */
	public static void destroy() {
		if ( functionProvider == null )
			return;

		AL.destroy();

		libraryCapabilities = null;

		functionProvider.free();
		functionProvider = null;
	}

	/** Returns the {@link FunctionProviderLocal} for the OpenAL native library. */
	public static FunctionProviderLocal getFunctionProvider() {
		return functionProvider;
	}

	/**
	 * Returns the {@link ALCCapabilities} of the device of the OpenAL context that is current in the current thread or process
	 * (see {@link AL#getCurrentContext()}).
	 *
	 * <p>If no OpenAL context is available, a special {@link ALCCapabilities} instance will be returned that will contain function pointers loaded directly
	 * from the OpenAL native library, for no particular device. This allows calling ALC functions (e.g. {@link ALC10#alcOpenDevice alcOpenDevice}) before
	 * any device has been opened yet.</p>
	 */
	public static ALCCapabilities getCapabilities() {
		ALContext context = AL.getCurrentContext();
		ALCCapabilities caps = context != null ? context.getDevice().getCapabilities() : libraryCapabilities;
		if ( caps == null )
			throw new IllegalStateException("No ALCCapabilities instance available. Make sure OpenAL has been loaded correctly.");

		return caps;
	}

	/**
	 * Creates a new {@link ALCCapabilities} instance for the specified OpenAL device.
	 *
	 * @return the ALCCapabilities instance
	 */
	static ALCCapabilities createCapabilities(long device) {
		// We don't have an ALCCapabilities instance when this method is called
		// so we have to use the native bindings directly.
		long GetIntegerv = functionProvider.getFunctionAddress("alcGetIntegerv");
		long GetString = functionProvider.getFunctionAddress("alcGetString");
		long IsExtensionPresent = functionProvider.getFunctionAddress("alcIsExtensionPresent");

		if ( GetIntegerv == NULL || GetString == NULL || IsExtensionPresent == NULL )
			throw new IllegalStateException("Core ALC functions could not be found. Make sure that OpenAL has been loaded.");

		APIBuffer __buffer = apiBuffer();

		invokePIIPV(GetIntegerv, device, ALC_MAJOR_VERSION, 1, __buffer.address());
		invokePIIPV(GetIntegerv, device, ALC_MINOR_VERSION, 1, __buffer.address(4));

		int majorVersion = __buffer.intValue(0);
		int minorVersion = __buffer.intValue(4);

		int[][] ALC_VERSIONS = {
			{ 0, 1 },  // ALC 1
		};

		Set<String> supportedExtensions = new HashSet<String>(16);

		for ( int major = 1; major <= ALC_VERSIONS.length; major++ ) {
			int[] minors = ALC_VERSIONS[major - 1];
			for ( int minor : minors ) {
				if ( major < majorVersion || (major == majorVersion && minor <= minorVersion) )
					supportedExtensions.add("OpenALC" + Integer.toString(major) + Integer.toString(minor));
			}
		}

		// Parse EXTENSIONS string
		String extensionsString = memDecodeUTF8(checkPointer(invokePIP(GetString, device, ALC_EXTENSIONS)));

		StringTokenizer tokenizer = new StringTokenizer(extensionsString);
		while ( tokenizer.hasMoreTokens() ) {
			String extName = tokenizer.nextToken();
			__buffer.reset().stringParamASCII(extName, true);
			if ( invokePPZ(IsExtensionPresent, device, __buffer.address()) )
				supportedExtensions.add(extName);
		}

		return new ALCCapabilities(getFunctionProvider(), device, supportedExtensions);
	}

	static <T> T checkExtension(String extension, T functions, boolean supported) {
		if ( supported )
			return functions;
		else {
			apiLog("[ALC] " + extension + " was reported as available but an entry point is missing.");
			return null;
		}
	}

}