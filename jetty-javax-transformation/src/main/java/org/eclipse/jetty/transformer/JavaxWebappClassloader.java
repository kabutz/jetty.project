//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.transformer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public class JavaxWebappClassloader extends WebAppClassLoader
{

    private static final JavaxTransfomer INSTANCE = new JavaxTransfomer();

    public JavaxWebappClassloader(Context context) throws IOException
    {
        super(context);
        addTransformer(INSTANCE);
    }

    static class JavaxTransfomer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException
        {
            try
            {
                ClassReader cr = new ClassReader(new ByteArrayInputStream(classfileBuffer));
                ClassWriter cw = new ClassWriter(0);

                ClassRemapper classRemapper = new ClassRemapper(cw, new JavaxRemapper());

                cr.accept(classRemapper, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            }
            catch (IOException e)
            {
                IllegalClassFormatException icfe = new IllegalClassFormatException(e.getMessage());
                icfe.initCause(e);
                throw icfe;
            }
        }
    }

    static class JavaxRemapper extends Remapper
    {

        private static final String JAVAX_SERVLET = "javax/servlet/";

        private static final String JAKARTA_SERVLET = "jakarta/servlet/";

        @Override
        public String map(String internalName)
        {
            if (internalName.startsWith(JAVAX_SERVLET))
            {
                return JAKARTA_SERVLET + internalName.substring(JAVAX_SERVLET.length());
            }
            return internalName;
        }

        @Override
        public String mapType(String internalName)
        {
            if (internalName.startsWith(JAVAX_SERVLET))
            {
                return JAKARTA_SERVLET + internalName.substring(JAVAX_SERVLET.length());
            }
            return super.mapType(internalName);
        }

    }

}
