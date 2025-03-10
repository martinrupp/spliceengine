/*
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Some parts of this source code are based on Apache Derby, and the following notices apply to
 * Apache Derby:
 *
 * Apache Derby is a subproject of the Apache DB project, and is licensed under
 * the Apache License, Version 2.0 (the "License"); you may not use these files
 * except in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Splice Machine, Inc. has modified the Apache Derby code in this file.
 *
 * All such Splice Machine modifications are Copyright 2012 - 2020 Splice Machine, Inc.,
 * and are licensed to you under the GNU Affero General Public License.
 */

package com.splicemachine.db.iapi.services.cache;

import com.splicemachine.db.iapi.services.sanity.SanityManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;

public class ClassSize
{
    public static final int refSize;
    private static final int objectOverhead = 2; // references, not bytes!
    private static final int booleanSize = 4;
    private static final int charSize = 4; // Unicode
    private static final int shortSize = 4;
    private static final int intSize = 4;
    private static final int longSize = 8;
    private static final int floatSize = 4;
    private static final int doubleSize = 8;
    private static final int minObjectSize;

    private static boolean dummyCatalog = false; // Used when constructing the catalog to prevent recursion

    static boolean noGuess = false;
    // noGuess is used in unit testing.

    static boolean unitTest = false;
    // unitTest is used in unit testing

    private static final int[] wildGuess = {0,16};
    /* The standard wild guess of the size of an unknown class, the size of 16 references.
     * Used when the security manager will not let us look at the class fields.
     */

    /* Do not let the compiler see ClassSizeCatalog. Otherwise it will try to
     * compile it. This may fail because ClassSizeCatalog.java is not created
     * until everything else has been compiled. Bury ClassSizeCatalog in a string.
     */
    private static java.util.Hashtable catalog;
    static
    {
        try
        {
            catalog = (java.util.Hashtable)
              Class.forName( "com.splicemachine.db.iapi.services.cache.ClassSizeCatalog").newInstance();
        }
        catch( Exception ignored){}

        // Figure out whether this is a 32 or 64 bit machine.
        int tmpRefSize = fetchRefSizeFromSystemProperties();
        // If we didn't understand the properties, or were not allowed to read
        // them, use a heuristic.
        if (tmpRefSize < 4) {
            Runtime runtime = Runtime.getRuntime();
            runtime.gc();
            runtime.runFinalization();
            long memBase = runtime.totalMemory() - runtime.freeMemory();
            Object[] junk = new Object[10000];
            runtime.gc();
            runtime.runFinalization();
            long memUsed = runtime.totalMemory() - runtime.freeMemory() - memBase;
            int sz = (int)((memUsed + junk.length/2)/junk.length);
            tmpRefSize = ( 4 > sz) ? 4 : sz;
        }
        // Assign what we have found to the final variable.
        refSize = tmpRefSize;
        minObjectSize = 4*refSize;
    }

    /**
     * do not try to use the catalog.
     */
    public static void setDummyCatalog()
    {
        dummyCatalog = true;
    }
    /**
     * Get the estimate of the size of an object reference.
     *
     * @return the estimate in bytes.
     */
    public static int getRefSize()
    {
        return refSize;
    }

    /**
     * @return the estimate of the size of a primitive int
     */
    public static int getIntSize()
    {
        return intSize;
    }

    /**
     * The estimate of the size of a class instance depends on whether the JVM uses 32 or 64
     * bit addresses, that is it depends on the size of an object reference. It is a linear
     * function of the size of a reference, e.g.
     *    24 + 5*r
     * where r is the size of a reference (usually 4 or 8 bytes).
     *
     * This method returns the coefficients of the linear function, e.g. {24, 5} in the above
     * example.
     *
     * @param cl A class whose instance size is to be estimated
     * @return an array of 2 integers. The first integer is the constant part of the function,
     *         the second is the reference size coefficient.
     */
    public static int[] getSizeCoefficients( Class cl)
    {
        int[] coeff = {0, objectOverhead};


        
        for( ; null != cl; cl = cl.getSuperclass())
        {
           Field[] field = cl.getDeclaredFields();
            if( null != field)
            {
                for (Field aField : field) {
                    if (!Modifier.isStatic(aField.getModifiers())) {
                        Class fieldClass = aField.getType();
                        if (fieldClass.isArray() || !fieldClass.isPrimitive())
                            coeff[1]++;
                        else // Is simple primitive
                        {
                            String name = fieldClass.getName();

                            if (name.equals("int") || name.equals("I"))
                                coeff[0] += intSize;
                            else if (name.equals("long") || name.equals("J"))
                                coeff[0] += longSize;
                            else if (name.equals("boolean") || name.equals("Z"))
                                coeff[0] += booleanSize;
                            else if (name.equals("short") || name.equals("S"))
                                coeff[0] += shortSize;
                            else if (name.equals("byte") || name.equals("B"))
                                coeff[0] += 1;
                            else if (name.equals("char") || name.equals("C"))
                                coeff[0] += charSize;
                            else if (name.equals("float") || name.equals("F"))
                                coeff[0] += floatSize;
                            else if (name.equals("double") || name.equals("D"))
                                coeff[0] += doubleSize;
                            else // What is this??
                                coeff[1]++; // Make a guess: one reference (?)
                        }
                    }
                }
            }
        }
        return coeff;
    } // end of getSizeCoefficients

    /**
     * Estimate the static space taken up by a class instance given the coefficients
     * returned by getSizeCoefficients.
     *
     * @param coeff the coefficients
     *
     * @return the size estimate, in bytes
     */
    public static int estimateBaseFromCoefficients( int[] coeff)
    {
        int size = coeff[0] + coeff[1]*refSize;
        // Round up to a multiple of 8
        size = (size + 7)/8;
        size *= 8;
        return (size < minObjectSize) ? minObjectSize : size;
    } // end of estimateBaseFromCoefficients

    /**
     * Estimate the static space taken up by a class instance from cataloged coefficients.
     *
     * @param cls the class
     *
     * @return the size estimate, in bytes
     *
     * @see #estimateBaseFromCoefficients
     * @see #getSizeCoefficients
     * see com.splicemachine.derbyBuild.ClassSizeCrawler
     */
    public static int estimateBaseFromCatalog( Class cls)
    {
        return estimateBaseFromCatalog( cls, false);
    }
    
    private static int estimateBaseFromCatalog( Class cls, boolean addToCatalog)
    {
        if( dummyCatalog)
            return 0;
        
        if( SanityManager.DEBUG)
			SanityManager.ASSERT( catalog != null, "The class size catalog could not be initialized.");
        
        int[] coeff = (int[]) catalog.get( cls.getName());
        if( coeff == null)
        {
            try
            {
                coeff = getSizeCoefficients( cls);
            }
            catch( Throwable t)
            {
                if( noGuess)
                    return -2;
                coeff = wildGuess;
            }
            if( addToCatalog)
                catalog.put( cls.getName(), coeff);
        }
        return estimateBaseFromCoefficients( coeff);
    } // end of estimateBaseFromCatalog


    /**
     * Estimate the static space taken up by a class instance. Save the coefficients
     * in a catalog.
     *
     * @param cls the class
     *
     * @return the size estimate, in bytes
     *
     * @see #estimateBaseFromCoefficients
     * @see #getSizeCoefficients
     * see com.splicemachine.derbyBuild.ClassSizeCrawler
     */
    public static int estimateAndCatalogBase( Class cls)
    {
        return estimateBaseFromCatalog( cls, true);
    } // end of estimateAndCatalogBase

    /**
     * Estimate the static space taken up by the fields of a class. This includes the space taken
     * up by by references (the pointer) but not by the referenced object. So the estimated
     * size of an array field does not depend on the size of the array. Similarly the size of
     * an object (reference) field does not depend on the object.
     *
     * @return the size estimate in bytes.
     *
     * Note that this method will throw a SecurityException if the SecurityManager does not
     * let this class execute the method Class.getDeclaredFields(). If this is a concern try
     * to compute the size coefficients at build time.
     * see com.splicemachine.derbyBuild.ClassSizeCrawler
     * @see #estimateBaseFromCatalog
     */
    public static int estimateBase( Class cl)
    {
        return estimateBaseFromCoefficients( getSizeCoefficients( cl));
    } // End of estimateBase

    /**
     * @return the estimated overhead of an array. The estimated size of an x[n] array is
     * estimateArrayOverhead() + n*sizeOf(x).
     */
    public static int estimateArrayOverhead()
    {
        return minObjectSize;
    }
    
    /**
     * Estimate the size of a Hashtable entry. In Java 1.2 we can use Map.entry, but this is not
     * available in earlier versions of Java.
     *
     * @return the estimate, in bytes
     */
    public static int estimateHashEntrySize()
    {
        return objectOverhead + 3*refSize;
    }

    /**
     * Estimate the size of a string.
     *
     * @return the estimated size, in bytes
     */
    public static int estimateMemoryUsage( String str)
    {
        if( null == str)
            return 0;
        // Since Java uses Unicode assume that each character takes 2 bytes
        return 2*str.length();
    }

    /**
     * Tries to determine the reference size in bytes by checking whether the
     * VM we're running in is 32 or 64 bit by looking at the system properties.
     *
     * @return The reference size in bytes as specified or implied by the VM,
     *      or {@code -1} if the reference size couldn't be determined.
     */
    private static int fetchRefSizeFromSystemProperties() {
        // Try the direct way first, by looking for 'sun.arch.data.model'
        String dataModel = getSystemProperty("sun.arch.data.model");
        try {
            return (Integer.parseInt(dataModel) / 8);
        } catch (NumberFormatException ignored) {}

        // Try 'os.arch'
        String arch = getSystemProperty("os.arch");
        // See if we recognize the property value.
        if (arch != null) {
            // Is it a known 32 bit architecture?
            String[] b32 = {"i386", "x86", "sparc"};
            if (Arrays.asList(b32).contains(arch)) return 4; // 4 bytes per ref
            // Is it a known 64 bit architecture?
            String[] b64 = {"amd64", "x86_64", "sparcv9"};
            if (Arrays.asList(b64).contains(arch)) return 8; // 8 bytes per ref
        }

        // Didn't find out anything.
        if (SanityManager.DEBUG) {
            SanityManager.DEBUG_PRINT(
                    "REFSIZE", "Bitness undetermined, sun.arch.data.model='" +
                    dataModel + "', os.arch='" + arch + "'");
        }
        return -1;
    }

    /**
     * Attempts to read the specified system property.
     *
     * @param propName name of the system property to read
     * @return The property value, or {@code null} if it doesn't exist or the
     *      required permission to read the property is missing.
     */
    private static String getSystemProperty(final String propName) {
        try {
            return (String)AccessController.doPrivileged(
                    new PrivilegedAction() {
                        public Object run() {
                            return System.getProperty(propName, null);
                        }
                });
        } catch (SecurityException se) {
            // Ignore exception and return null.
            return null;
        }
    }
}
