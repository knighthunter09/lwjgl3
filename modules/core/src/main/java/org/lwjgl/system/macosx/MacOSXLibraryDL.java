/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.system.macosx;

import org.lwjgl.system.SharedLibrary;

import static org.lwjgl.system.APIUtil.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.macosx.DynamicLinkLoader.*;

/** Implements a {@link SharedLibrary} on the MacOS X using dlopen. */
public class MacOSXLibraryDL extends MacOSXLibrary {

	public MacOSXLibraryDL(String name) {
		super(dlopen(name, RTLD_LAZY | RTLD_GLOBAL), name);

		if ( address() == NULL )
			throw new RuntimeException("Failed to dynamically load library: " + name + "(error = " + dlerror() + ")");

		apiLog("Loaded native library: " + name);
	}

	@Override
	public long getFunctionAddress(CharSequence name) {
		return dlsym(address(), name);
	}

	@Override
	public void free() {
		dlclose(address());
	}

}