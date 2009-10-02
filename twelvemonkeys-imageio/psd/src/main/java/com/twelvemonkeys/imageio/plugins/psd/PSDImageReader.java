/*
 * Copyright (c) 2008, Harald Kuhr
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name "TwelveMonkeys" nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.twelvemonkeys.imageio.plugins.psd;

import com.twelvemonkeys.image.ImageUtil;
import com.twelvemonkeys.imageio.ImageReaderBase;
import com.twelvemonkeys.imageio.util.IndexedImageTypeSpecifier;

import javax.imageio.*;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.*;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * ImageReader for Adobe Photoshop Document format.
 *
 * @see <a href="http://www.fileformat.info/format/psd/egff.htm">Adobe Photoshop File Format Summary<a>
 * @author <a href="mailto:harald.kuhr@gmail.com">Harald Kuhr</a>
 * @author last modified by $Author: haraldk$
 * @version $Id: PSDImageReader.java,v 1.0 Apr 29, 2008 4:45:52 PM haraldk Exp$
 */
// TODO: Implement meta data reading
// TODO: Implement layer reading
// TODO: Allow reading separate (or some?) layers
// TODO: Consider Romain Guy's Java 2D implementation of PS filters for the blending modes in layers
// http://www.curious-creature.org/2006/09/20/new-blendings-modes-for-java2d/
// See http://www.codeproject.com/KB/graphics/PSDParser.aspx
// See http://www.adobeforums.com/webx?14@@.3bc381dc/0
public class PSDImageReader extends ImageReaderBase {
    private PSDHeader mHeader;
    private PSDColorData mColorData;
    private List<PSDImageResource> mImageResources;
    private PSDGlobalLayerMask mGlobalLayerMask;
    private List<PSDLayerInfo> mLayerInfo;
    private ICC_ColorSpace mColorSpace;

    protected PSDImageReader(final ImageReaderSpi pOriginatingProvider) {
        super(pOriginatingProvider);
    }

    protected void resetMembers() {
        mHeader = null;
        mColorData = null;
        mImageResources = null;
        mColorSpace = null;
    }

    public int getWidth(final int pIndex) throws IOException {
        checkBounds(pIndex);
        readHeader();
        return mHeader.mWidth;
    }

    public int getHeight(final int pIndex) throws IOException {
        checkBounds(pIndex);
        readHeader();
        return mHeader.mHeight;
    }

    @Override
    public ImageTypeSpecifier getRawImageType(final int pIndex) throws IOException {
        return getRawImageTypeInternal(pIndex);
    }

    private ImageTypeSpecifier getRawImageTypeInternal(final int pIndex) throws IOException {
        checkBounds(pIndex);
        readHeader();

        ColorSpace cs;

        switch (mHeader.mMode) {
            case PSD.COLOR_MODE_MONOCHROME:
                if (mHeader.mChannels == 1 && mHeader.mBits == 1) {
                    return ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_BYTE_BINARY);
                }

                throw new IIOException(
                        String.format("Unsupported channel count/bit depth for Monochrome PSD: %d channels/%d bits", mHeader.mChannels, mHeader.mBits)
                );

            case PSD.COLOR_MODE_INDEXED:
                // TODO: 16 bit indexed?!
                if (mHeader.mChannels == 1 && mHeader.mBits == 8) {
                    return IndexedImageTypeSpecifier.createFromIndexColorModel(mColorData.getIndexColorModel());
                }

                throw new IIOException(
                        String.format("Unsupported channel count/bit depth for Indexed Color PSD: %d channels/%d bits", mHeader.mChannels, mHeader.mBits)
                );

            case PSD.COLOR_MODE_DUOTONE:
                // NOTE: Duotone (whatever that is) should be treated as gray scale
                // Fall-through
            case PSD.COLOR_MODE_GRAYSCALE:
                if (mHeader.mChannels == 1 && mHeader.mBits == 8) {
                    return ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_BYTE_GRAY);
                }
                else if (mHeader.mChannels == 1 && mHeader.mBits == 16) {
                    return ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_USHORT_GRAY);
                }

                throw new IIOException(
                        String.format("Unsupported channel count/bit depth for Gray Scale PSD: %d channels/%d bits", mHeader.mChannels, mHeader.mBits)
                );

            case PSD.COLOR_MODE_RGB:
                cs = getEmbeddedColorSpace();
                if (cs == null) {
                    cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
                }

                if (mHeader.mChannels == 3 && mHeader.mBits == 8) {
                    return ImageTypeSpecifier.createBanded(cs, new int[] {0, 1, 2}, new int[] {0, 0, 0}, DataBuffer.TYPE_BYTE, false, false);
                }
                else if (mHeader.mChannels >= 4 && mHeader.mBits == 8) {
                    return ImageTypeSpecifier.createBanded(cs, new int[] {0, 1, 2, 3}, new int[] {0, 0, 0, 0}, DataBuffer.TYPE_BYTE, true, false);
                }
                else if (mHeader.mChannels == 3 && mHeader.mBits == 16) {
                    return ImageTypeSpecifier.createBanded(cs, new int[] {0, 1, 2}, new int[] {0, 0, 0}, DataBuffer.TYPE_USHORT, false, false);
                }
                else if (mHeader.mChannels >= 4 && mHeader.mBits == 16) {
                    return ImageTypeSpecifier.createBanded(cs, new int[] {0, 1, 2, 3}, new int[] {0, 0, 0, 0}, DataBuffer.TYPE_USHORT, true, false);
                }

                throw new IIOException(
                        String.format("Unsupported channel count/bit depth for RGB PSD: %d channels/%d bits", mHeader.mChannels, mHeader.mBits)
                );

            case PSD.COLOR_MODE_CMYK:
                cs = getEmbeddedColorSpace();
                if (cs == null) {
                    cs = CMYKColorSpace.getInstance();
                }

                if (mHeader.mChannels == 4 &&  mHeader.mBits == 8) {
                    return ImageTypeSpecifier.createBanded(cs, new int[] {0, 1, 2, 3}, new int[] {0, 0, 0, 0}, DataBuffer.TYPE_BYTE, false, false);
                }
                else if (mHeader.mChannels == 5 &&  mHeader.mBits == 8) {
                    return ImageTypeSpecifier.createBanded(cs, new int[] {0, 1, 2, 3, 4}, new int[] {0, 0, 0, 0, 0}, DataBuffer.TYPE_BYTE, true, false);
                }
                else if (mHeader.mChannels == 4 &&  mHeader.mBits == 16) {
                    return ImageTypeSpecifier.createBanded(cs, new int[] {0, 1, 2, 3}, new int[] {0, 0, 0, 0}, DataBuffer.TYPE_USHORT, false, false);
                }
                else if (mHeader.mChannels == 5 &&  mHeader.mBits == 16) {
                    return ImageTypeSpecifier.createBanded(cs, new int[] {0, 1, 2, 3, 4}, new int[] {0, 0, 0, 0, 0}, DataBuffer.TYPE_USHORT, true, false);
                }

                throw new IIOException(
                        String.format("Unsupported channel count/bit depth for CMYK PSD: %d channels/%d bits", mHeader.mChannels, mHeader.mBits)
                );
            default:
                throw new IIOException(
                        String.format("Unsupported PSD MODE: %s (%d channels/%d bits)", mHeader.mMode, mHeader.mChannels, mHeader.mBits)
                );
        }
    }

    public Iterator<ImageTypeSpecifier> getImageTypes(final int pIndex) throws IOException {
        // TODO: Check out the custom ImageTypeIterator and ImageTypeProducer used in the Sun provided JPEGImageReader
        // Could use similar concept to create lazily-created ImageTypeSpecifiers (util candidate, based on FilterIterator?)

        // Get the raw type. Will fail for unsupported types
        ImageTypeSpecifier rawType = getRawImageTypeInternal(pIndex);

        ColorSpace cs = rawType.getColorModel().getColorSpace();
        List<ImageTypeSpecifier> types = new ArrayList<ImageTypeSpecifier>();

        switch (mHeader.mMode) {
            case PSD.COLOR_MODE_RGB:
                // Prefer interleaved versions as they are much faster to display
                if (mHeader.mChannels == 3 && mHeader.mBits == 8) {
                    // Basically same as BufferedImage.TYPE_3BYTE_BGR
                    types.add(ImageTypeSpecifier.createInterleaved(cs, new int[] {2, 1, 0}, DataBuffer.TYPE_BYTE, false, false));
                }
                else if (mHeader.mChannels >= 4 && mHeader.mBits == 8) {
                    // Basically same as BufferedImage.TYPE_4BYTE_ABGR
                    types.add(ImageTypeSpecifier.createInterleaved(cs, new int[] {3, 2, 1, 0}, DataBuffer.TYPE_BYTE, true, false));
                }
                else if (mHeader.mChannels == 3 && mHeader.mBits == 16) {
                    types.add(ImageTypeSpecifier.createInterleaved(cs, new int[] {2, 1, 0}, DataBuffer.TYPE_USHORT, false, false));
                }
                else if (mHeader.mChannels >= 4 && mHeader.mBits == 16) {
                    types.add(ImageTypeSpecifier.createInterleaved(cs, new int[] {3, 2, 1, 0}, DataBuffer.TYPE_USHORT, true, false));
                }
                break;
            case PSD.COLOR_MODE_CMYK:
                // Prefer interleaved versions as they are much faster to display
                // TODO: We should convert these to their RGB equivalents while reading for the common-case,
                // as Java2D is extremely slow displaying custom images.
                // Converting to RGB is also correct behaviour, according to the docs.
                if (mHeader.mChannels == 4 &&  mHeader.mBits == 8) {
                    types.add(ImageTypeSpecifier.createInterleaved(cs, new int[] {3, 2, 1, 0}, DataBuffer.TYPE_BYTE, false, false));
                }
                else if (mHeader.mChannels == 5 &&  mHeader.mBits == 8) {
                    types.add(ImageTypeSpecifier.createInterleaved(cs, new int[] {4, 3, 2, 1, 0}, DataBuffer.TYPE_BYTE, true, false));
                }
                else if (mHeader.mChannels == 4 &&  mHeader.mBits == 16) {
                    types.add(ImageTypeSpecifier.createInterleaved(cs, new int[]{3, 2, 1, 0}, DataBuffer.TYPE_USHORT, false, false));
                }
                else if (mHeader.mChannels == 5 &&  mHeader.mBits == 16) {
                    types.add(ImageTypeSpecifier.createInterleaved(cs, new int[] {4, 3, 2, 1, 0}, DataBuffer.TYPE_USHORT, true, false));
                }
                break;
            default:
                // Just stick to the raw type
        }

        // Finally add the
        types.add(rawType);

        return types.iterator();
    }

    private ColorSpace getEmbeddedColorSpace() throws IOException {
        readImageResources(true);
        // TODO: Skip this, requires storing some stream offsets
        readLayerAndMaskInfo(false);

        if (mColorSpace == null) {
            ICC_Profile profile = null;
            for (PSDImageResource resource : mImageResources) {
                if (resource instanceof ICCProfile) {
                    profile = ((ICCProfile) resource).getProfile();
                    break;
                }
            }

            mColorSpace = profile == null ? null : new ICC_ColorSpace(profile);
        }

        return mColorSpace;
    }

    public BufferedImage read(final int pIndex, final ImageReadParam pParam) throws IOException {
        checkBounds(pIndex);

        readHeader();

        readImageResources(false);
        readLayerAndMaskInfo(false);

        // TODO: Test if explicit destination is compatible or throw IllegalArgumentException
        BufferedImage image = getDestination(pParam, getImageTypes(pIndex), mHeader.mWidth, mHeader.mHeight);

        processImageStarted(pIndex);

        final Rectangle source = new Rectangle();
        final Rectangle dest = new Rectangle();
        computeRegions(pParam, mHeader.mWidth, mHeader.mHeight, image, source, dest);

        /*
        NOTE: It seems safe to just leave this out for now. The only thing we need is to support sub sampling.
        Sun's readers does not support arbitrary destination formats.

        // TODO: Create temp raster in native format w * 1
        // Read (sub-sampled) row into temp raster (skip other rows)
        // If color model (color space) is not RGB, do color convert op
        // Otherwise, copy "through" ColorMode?l
        // Copy pixels from temp raster
        // If possible, leave the destination image "untouched" (accelerated)

        // TODO: Doing a per line color convert will be expensive, as data is channelled...
        // Will need to either convert entire image, or skip back/forth between channels...

        // TODO: Banding...

        ImageTypeSpecifier spec = getRawImageType(pIndex);
        BufferedImage temp = spec.createBufferedImage(getWidth(pIndex), 1);
        temp.getRaster();

        if (...)
        ColorConvertOp convert = new ColorConvertOp(...);

        */

        final int xSub;
        final int ySub;

        if (pParam == null) {
            xSub = ySub = 1;
        }
        else {
            xSub = pParam.getSourceXSubsampling();
            ySub = pParam.getSourceYSubsampling();
        }

        int[] offsets = null;
        int compression = mImageInput.readShort();

        switch (compression) {
            case PSD.COMPRESSION_NONE:
                break;
            case PSD.COMPRESSION_RLE:
                // NOTE: Offsets will allow us to easily skip rows before AOI
                offsets = new int[mHeader.mChannels * mHeader.mHeight];
                for (int i = 0; i < offsets.length; i++) {
                    offsets[i] = mImageInput.readUnsignedShort();
                }
                break;
            case PSD.COMPRESSION_ZIP:
                // TODO: Could probably use the ZIPDecoder (DeflateDecoder) here..
            case PSD.COMPRESSION_ZIP_PREDICTON:
                // TODO: Need to find out if the normal java.util.zip can handle this...
                // Could be same as PNG prediction? Read up...
                throw new IIOException("ZIP compression not supported yet");
            default:
                throw new IIOException("Unknown compression type: " + compression);
        }

        readImageData(image, source, dest, xSub, ySub, offsets, compression);

        if (abortRequested()) {
            processReadAborted();
        }
        else {
            processImageComplete();
        }

        return image;
    }

    private void readImageData(final BufferedImage pImage,
                               final Rectangle pSource, final Rectangle pDest,
                               final int pXSub, final int pYSub,
                               final int[] pOffsets, final int pCompression) throws IOException {
        // TODO: Refactor so that we loop through channels here, and read one channel in each of the methods below

        switch (mHeader.mBits) {
            case 1:
                read1bitData(pImage.getRaster(), pImage.getColorModel(), pSource, pDest, pXSub, pYSub, pOffsets, pCompression == PSD.COMPRESSION_RLE);
                break;
            case 8:
                read8bitData(pImage.getRaster(), pImage.getColorModel(), pSource, pDest, pXSub, pYSub, pOffsets, pCompression == PSD.COMPRESSION_RLE);
                break;
            case 16:
                read16bitData(pImage.getRaster(), pImage.getColorModel(), pSource, pDest, pXSub, pYSub, pOffsets, pCompression == PSD.COMPRESSION_RLE);
                break;
            default:
                throw new IIOException("Unknown bit depth: " + mHeader.mBits);
        }
    }

    private void read16bitData(final WritableRaster pRaster, final ColorModel pDestinationColorModel,
                              final Rectangle pSource, final Rectangle pDest,
                              final int pXSub, final int pYSub,
                              final int[] pRowOffsets, final boolean pRLECompressed) throws IOException {
        final int channels = pRaster.getNumBands();

        // TODO: FixMe: Use real source color model from native (raw) image type, and convert if needed
        ColorModel sourceColorModel = pDestinationColorModel;
        // TODO: This raster is 3-4 times longer than needed, depending on number of channels...
        WritableRaster rowRaster = sourceColorModel.createCompatibleWritableRaster(mHeader.mWidth, 1);
        final short[] row = ((DataBufferUShort) rowRaster.getDataBuffer()).getData();

        final boolean isCMYK = sourceColorModel.getColorSpace().getType() == ColorSpace.TYPE_CMYK;
        final int colorComponents = sourceColorModel.getColorSpace().getNumComponents();

        DataBufferUShort buffer = (DataBufferUShort) pRaster.getDataBuffer();
        final boolean banded = buffer.getNumBanks() > 1;

        short[] data = null;
        int x = 0, y = 0, c = 0;
        try {
            for (c = 0; c < channels; c++) {
                data = banded ? buffer.getData(c) : buffer.getData();

                for (y = 0; y < mHeader.mHeight; y++) {
                    // Length is in shorts!?
                    int length = 2 * (pRLECompressed ? pRowOffsets[c * mHeader.mHeight + y] : mHeader.mWidth);

                    // TODO: Sometimes need to read the line y == source.y + source.height...
                    // Read entire line, if within source region and sampling
                    if (y >= pSource.y && y < pSource.y + pSource.height && y % pYSub == 0) {
                        if (pRLECompressed) {
                            DataInputStream input = PSDUtil.createPackBitsStream(mImageInput, length);
                            try {
                                for (x = 0; x < mHeader.mWidth; x++) {
                                    row[x] = input.readShort();
                                }
                            }
                            finally {
                                input.close();
                            }
                        }
                        else {
                            mImageInput.readFully(row, 0, mHeader.mWidth);
                        }

                        // TODO: Destination offset...??
                        // Copy line sub sampled into real data
                        int offset = banded ?
                                (y - pSource.y) / pYSub * pDest.width :
                                (y - pSource.y) / pYSub * pDest.width * channels + (channels - 1 - c);
                        for (int i = 0; i < pDest.width; i++) {
                            short value = row[pSource.x + i * pXSub];

                            // CMYK values are stored inverted, but alpha is not
                            if (isCMYK && c < colorComponents) {
                                value = (short) (65535 - value & 0xffff);
                            }

                            if (banded) {
                                data[offset + i] = value;
                            }
                            else {
                                data[offset + i * channels] = value;
                            }
                        }
                    }
                    else {
                        mImageInput.skipBytes(length);
                    }

                    if (abortRequested()) {
                        break;
                    }
                    processImageProgress((c * y * 100) / mHeader.mChannels * mHeader.mHeight);
                }

                if (abortRequested()) {
                    break;
                }
            }
        }
        catch (IOException e) {
            System.err.println("c: " + c);
            System.err.println("y: " + y);
            System.err.println("x: " + x);
            throw e;
        }
        catch (IndexOutOfBoundsException e) {
            e.printStackTrace();
            System.out.println("data.length: " + data.length);
            System.err.println("c: " + c);
            System.err.println("y: " + y);
            System.err.println("x: " + x);
            throw e;
        }

        // TODO: Alpha in 16 bits samples!?
        // Compose out the background of the semi-transparent pixels, as PS somehow has the background composed in
//        decomposeAlpha(sourceColorModel, data, pDest.width, pDest.height, channels);
    }

    private void read8bitData(final WritableRaster pRaster, final ColorModel pDestinationColorModel,
                              final Rectangle pSource, final Rectangle pDest,
                              final int pXSub, final int pYSub,
                              final int[] pRowOffsets, final boolean pRLECompressed) throws IOException
    {
        final int channels = pRaster.getNumBands();

        // TODO: FixMe: Use real source color model from native (raw) image type, and convert if needed
        ColorModel sourceColorModel = pDestinationColorModel;
        // TODO: This raster is 3-4 times longer than needed, depending on number of channels...
        WritableRaster rowRaster = sourceColorModel.createCompatibleWritableRaster(mHeader.mWidth, 1);
        final byte[] row = ((DataBufferByte) rowRaster.getDataBuffer()).getData();

        final boolean isCMYK = sourceColorModel.getColorSpace().getType() == ColorSpace.TYPE_CMYK;
        final int colorComponents = sourceColorModel.getColorSpace().getNumComponents();

        DataBufferByte buffer = (DataBufferByte) pRaster.getDataBuffer();
        final boolean banded = buffer.getNumBanks() > 1;

        byte[] data = null;
        int x = 0, y = 0, c = 0;
        try {
            for (c = 0; c < channels; c++) {
                data = banded ? buffer.getData(c) : buffer.getData();

                for (y = 0; y < mHeader.mHeight; y++) {
                    int length = pRLECompressed ? pRowOffsets[c * mHeader.mHeight + y] : mHeader.mWidth;

                    // TODO: Sometimes need to read the line y == source.y + source.height...
                    // Read entire line, if within source region and sampling
                    if (y >= pSource.y && y < pSource.y + pSource.height && y % pYSub == 0) {
                        if (pRLECompressed) {
                            DataInputStream input = PSDUtil.createPackBitsStream(mImageInput, length);
                            try {
                                input.readFully(row, 0, mHeader.mWidth);
                            }
                            finally {
                                input.close();
                            }
                        }
                        else {
                            mImageInput.readFully(row, 0, mHeader.mWidth);
                        }

                        // TODO: If banded and not sub sampling/cmyk, we could just copy using System.arraycopy
                        // TODO: Destination offset...??
                        // Copy line sub sampled into real data
//                        int offset = (y - pSource.y) / pYSub * pDest.width * channels + (channels - 1 - c);
                        int offset = banded ?
                                (y - pSource.y) / pYSub * pDest.width :
                                (y - pSource.y) / pYSub * pDest.width * channels + (channels - 1 - c);
                        for (int i = 0; i < pDest.width; i++) {
                            byte value = row[pSource.x + i * pXSub];

                            // CMYK values are stored inverted, but alpha is not
                            if (isCMYK && c < colorComponents) {
                                value = (byte) (255 - value & 0xff);
                            }

                            if (banded) {
                                data[offset + i] = value;
                            }
                            else {
                                data[offset + i * channels] = value;
                            }
                        }
                    }
                    else {
                        mImageInput.skipBytes(length);
                    }

                    if (abortRequested()) {
                        break;
                    }
                    processImageProgress((c * y * 100) / mHeader.mChannels * mHeader.mHeight);
                }

                if (abortRequested()) {
                    break;
                }
            }
        }
        catch (IOException e) {
            System.err.println("c: " + c);
            System.err.println("y: " + y);
            System.err.println("x: " + x);
            throw e;
        }
        catch (IndexOutOfBoundsException e) {
            e.printStackTrace();
            System.out.println("data.length: " + data.length);
            System.err.println("c: " + c);
            System.err.println("y: " + y);
            System.err.println("x: " + x);
            throw e;
        }

        // Compose out the background of the semi-transparent pixels, as PS somehow has the background composed in
        decomposeAlpha(sourceColorModel, buffer, pDest.width, pDest.height, channels);
    }

    private void read1bitData(final WritableRaster pRaster, final ColorModel pDestinationColorModel,
                              final Rectangle pSource, final Rectangle pDest,
                              final int pXSub, final int pYSub,
                              final int[] pRowOffsets, final boolean pRLECompressed) throws IOException {
        final byte[] data = ((DataBufferByte) pRaster.getDataBuffer()).getData();

        // TODO: FixMe: Use real source color model from native (raw) image type, and convert if needed
        ColorModel sourceColorModel = pDestinationColorModel;
        WritableRaster rowRaster = sourceColorModel.createCompatibleWritableRaster(mHeader.mWidth, 1);
        final byte[] row = ((DataBufferByte) rowRaster.getDataBuffer()).getData();

        final int destWidth = (pDest.width + 7) / 8;

        int y = 0;
        try {
            for (y = 0; y < mHeader.mHeight; y++) {
                int length = pRLECompressed ? pRowOffsets[y] : mHeader.mWidth;

                // TODO: Sometimes need to read the line y == source.y + source.height...
                // Read entire line, if within source region and sampling
                if (y >= pSource.y && y < pSource.y + pSource.height && y % pYSub == 0) {
                    if (pRLECompressed) {
                        DataInputStream input = PSDUtil.createPackBitsStream(mImageInput, length);
                        try {
                            input.readFully(row, 0, row.length);
                        }
                        finally {
                            input.close();
                        }
                    }
                    else {
                        mImageInput.readFully(row, 0, row.length);
                    }

                    // TODO: Destination offset...??
                    int offset = (y - pSource.y) / pYSub * destWidth;
                    if (pXSub == 1 && pSource.x % 8 == 0) {
                        // Fast normal case, no sub sampling
                        for (int i = 0; i < destWidth; i++) {
                            byte value = row[pSource.x / 8 + i * pXSub];
                            // NOTE: Invert bits to match Java's default monochrome
                            data[offset + i] = (byte) (~value & 0xff);
                        }
                    }
                    else {
                        // Copy line sub sampled into real data
                        final int maxX = pSource.x + pSource.width;
                        int x = pSource.x;
                        for (int i = 0; i < destWidth; i++) {
                            byte result = 0;

                            for (int j = 0; j < 8 && x < maxX; j++) {
                                int bytePos = x / 8;

                                int sourceBitOff = 7 - (x % 8);
                                int mask = 1 << sourceBitOff;
                                int destBitOff = 7 - j;

                                // Shift bit into place
                                result |= ((row[bytePos] & mask) >> sourceBitOff) << destBitOff;

                                x += pXSub;
                            }

                            // NOTE: Invert bits to match Java's default monochrome
                            data[offset + i] = (byte) (~result & 0xff);
                        }
                    }
                }
                else {
                    mImageInput.skipBytes(length);
                }

                if (abortRequested()) {
                    break;
                }
                processImageProgress((y * 100) / mHeader.mHeight);
            }
        }
        catch (IOException e) {
            System.err.println("y: " + y);
            throw e;
        }
        catch (IndexOutOfBoundsException e) {
            e.printStackTrace();
            System.out.println("data.length: " + data.length);
            System.err.println("y: " + y);
            throw e;
        }
    }
    
    private void decomposeAlpha(final ColorModel pModel, final DataBufferByte pBuffer,
                                final int pWidth, final int pHeight, final int pChannels) {
        // TODO: Is the document background always white!?
        // TODO: What about CMYK + alpha?
        if (pModel.hasAlpha() && pModel.getColorSpace().getType() == ColorSpace.TYPE_RGB) {

            // TODO: Probably faster to do this in line..
            if (pBuffer.getNumBanks() > 1) {
                byte[][] data = pBuffer.getBankData();

                for (int y = 0; y < pHeight; y++) {
                    for (int x = 0; x < pWidth; x++) {
                        int offset = (x + y * pWidth);
                        // ARGB format
                        int alpha = data[pChannels - 1][offset] & 0xff;

                        if (alpha != 0) {
                            double normalizedAlpha = alpha / 255.0;

                            for (int i = 0; i < pChannels - 1; i++) {
                                data[i][offset] = decompose(data[i][offset] & 0xff, normalizedAlpha);
                            }
                        }
                        else {
                            for (int i = 0; i < pChannels - 1; i++) {
                                data[i][offset] = 0;
                            }
                        }
                    }
                }
            }
            else {
                byte[] data = pBuffer.getData();

                for (int y = 0; y < pHeight; y++) {
                    for (int x = 0; x < pWidth; x++) {
                        int offset = (x + y * pWidth) * pChannels;
                        // ABGR format
                        int alpha = data[offset] & 0xff;

                        if (alpha != 0) {
                            double normalizedAlpha = alpha / 255.0;

                            for (int i = 1; i < pChannels; i++) {
                                data[offset + i] = decompose(data[offset + i] & 0xff, normalizedAlpha);
                            }
                        }
                        else {
                            for (int i = 1; i < pChannels; i++) {
                                data[offset + i] = 0;
                            }
                        }
                    }
                }
            }
        }
    }

    private static byte decompose(final int pColor, final double pAlpha) {
        // Adapted from Computer Graphics: Principles and Practice (Foley et al.), p. 837
        double color = pColor / 255.0;
        return (byte) ((color / pAlpha - ((1 - pAlpha) / pAlpha)) * 255);
    }

    private void readHeader() throws IOException {
        assertInput();
        if (mHeader == null) {
            mHeader = new PSDHeader(mImageInput);

            /*
            Contains the required data to define the color mode.

            For indexed color images, the count will be equal to 768, and the mode data
            will contain the color table for the image, in non-interleaved order.

            For duotone images, the mode data will contain the duotone specification, 
            the format of which is not documented.  Non-Photoshop readers can treat
            the duotone image as a grayscale image, and keep the duotone specification
            around as a black box for use when saving the file.
             */
            if (mHeader.mMode == PSD.COLOR_MODE_INDEXED) {
                mColorData = new PSDColorData(mImageInput);
            }
            else {
                // Skip color mode data for other modes
                long length = mImageInput.readUnsignedInt();
                mImageInput.skipBytes(length);
            }

            // Don't need the header again
            mImageInput.flushBefore(mImageInput.getStreamPosition());
        }
    }

    private void readImageResources(final boolean pParseData) throws IOException {
        // TODO: Avoid unnecessary stream repositioning
        long pos = mImageInput.getFlushedPosition();
        mImageInput.seek(pos);

        long length = mImageInput.readUnsignedInt();

        if (pParseData && length > 0) {
            if (mImageResources == null) {
                mImageResources = new ArrayList<PSDImageResource>();
                long expectedEnd = mImageInput.getStreamPosition() + length;

                while (mImageInput.getStreamPosition() < expectedEnd) {
                    PSDImageResource resource = PSDImageResource.read(mImageInput);
                    mImageResources.add(resource);
                }

                if (mImageInput.getStreamPosition() != expectedEnd) {
                    throw new IIOException("Corrupt PSD document"); // ..or maybe just a bug in the reader.. ;-)
                }
            }
        }

        mImageInput.seek(pos + length + 4);
    }

    private void readLayerAndMaskInfo(final boolean pParseData) throws IOException {
        // TODO: Make sure we are positioned correctly
        long length = mImageInput.readUnsignedInt();
        if (pParseData && length > 0) {
            long pos = mImageInput.getStreamPosition();

            long layerInfoLength = mImageInput.readUnsignedInt();

            /*
             "Layer count. If it is a negative number, its absolute value is the number of
             layers and the first alpha channel contains the transparency data for the
             merged result."
             */
            // TODO: Figure out what the last part of that sentence means in practice...
            int layers = mImageInput.readShort();
//            System.out.println("layers: " + layers);

            PSDLayerInfo[] layerInfo = new PSDLayerInfo[Math.abs(layers)];
            for (int i = 0; i < layerInfo.length; i++) {
                layerInfo[i] = new PSDLayerInfo(mImageInput);
//                System.out.println("layerInfo[" + i + "]: " + layerInfo[i]);
            }
            mLayerInfo = Arrays.asList(layerInfo);

            for (PSDLayerInfo info : layerInfo) {
                for (PSDChannelInfo channelInfo : info.mChannelInfo) {
                    int compression = mImageInput.readUnsignedShort();
                    // 0: None, 1: PackBits RLE, 2: Zip, 3: Zip w/prediction
                    switch (compression) {
                        case PSD.COMPRESSION_NONE:
//                            System.out.println("Compression: None");
                            break;
                        case PSD.COMPRESSION_RLE:
//                            System.out.println("Compression: PackBits RLE");
                            break;
                        case PSD.COMPRESSION_ZIP:
//                            System.out.println("Compression: ZIP");
                            break;
                        case PSD.COMPRESSION_ZIP_PREDICTON:
//                            System.out.println("Compression: ZIP with prediction");
                            break;
                        default:
                            // TODO: Do we care, as we can just skip the data?
                            // We could issue a warning to the warning listener
                            throw new IIOException(String.format(
                                    "Unknown PSD compression: %d. Expected 0 (none), 1 (RLE), 2 (ZIP) or 3 (ZIP w/prediction).",
                                    compression
                            ));
                    }

                    // TODO: If RLE, the the image data starts with the byte counts
                    // for all the scan lines in the channel (LayerBottom*LayerTop), with
                    // each count stored as a two*byte value.
                    //                if (compression == 1) {
                    //                    mImageInput.skipBytes(channelInfo.mLength);
                    //                }

                    // TODO: Read channel image data (same format as composite image channel data)
                    mImageInput.skipBytes(channelInfo.mLength - 2);
                    //                if (channelInfo.mLength % 2 != 0) {
                    //                    mImageInput.readByte();
                    //                }
                }
            }

            // TODO: We seem to have some alignment issues here...
            // I'm always reading two bytes off..

            long read = mImageInput.getStreamPosition() - pos;
//            System.out.println("layerInfoLength: " + layerInfoLength);
//            System.out.println("layer info read: " + (read - 4)); // - 4 for the layerInfoLength field itself
            long diff = layerInfoLength - (read - 4);
//            System.out.println("diff: " + diff);
            mImageInput.skipBytes(diff);

            // TODO: Global LayerMaskInfo (18 bytes or more..?)
            // 4 (length), 2 (colorSpace), 8 (4 * 2 byte color components), 2 (opacity %), 1 (kind), variable (pad)
            long layerMaskInfoLength = mImageInput.readUnsignedInt();
//            System.out.println("GlobalLayerMaskInfo length: " + layerMaskInfoLength);
            if (layerMaskInfoLength > 0) {
                mGlobalLayerMask = new PSDGlobalLayerMask(mImageInput);
            }

            read = mImageInput.getStreamPosition() - pos;

            long toSkip = length - read;
//            System.out.println("toSkip: " + toSkip);
            mImageInput.skipBytes(toSkip);
        }
        else {
            mImageInput.skipBytes(length);
        }
    }

    /// Thumbnail support
    @Override
    public boolean readerSupportsThumbnails() {
        return true;
    }

    private List<PSDThumbnail> getThumbnailResources(final int pIndex) throws IOException {
        checkBounds(pIndex);

        readHeader();

        List<PSDThumbnail> thumbnails = null;

        if (mImageResources != null) {
            // TODO: Need flag here, to specify what resources to read...
            readImageResources(true);
            // TODO: Skip this, requires storing some stream offsets
            readLayerAndMaskInfo(false);

            for (PSDImageResource resource : mImageResources) {
                if (resource instanceof PSDThumbnail) {
                    if (thumbnails == null) {
                        thumbnails = new ArrayList<PSDThumbnail>();
                    }

                    thumbnails.add((PSDThumbnail) resource);
                }
            }
        }

        return thumbnails;
    }

    @Override
    public int getNumThumbnails(int pIndex) throws IOException {
        List<PSDThumbnail> thumbnails = getThumbnailResources(pIndex);

        return thumbnails == null ? 0 : thumbnails.size();
    }

    private PSDThumbnail getThumbnailResource(int pImageIndex, int pThumbnailIndex) throws IOException {
        List<PSDThumbnail> thumbnails = getThumbnailResources(pImageIndex);

        if (thumbnails == null) {
            throw new IndexOutOfBoundsException(String.format("thumbnail index %d > 0", pThumbnailIndex));
        }

        return thumbnails.get(pThumbnailIndex);
    }

    @Override
    public int getThumbnailWidth(int pImageIndex, int pThumbnailIndex) throws IOException {
        return getThumbnailResource(pImageIndex, pThumbnailIndex).getWidth();
    }

    @Override
    public int getThumbnailHeight(int pImageIndex, int pThumbnailIndex) throws IOException {
        return getThumbnailResource(pImageIndex, pThumbnailIndex).getHeight();
    }

    @Override
    public BufferedImage readThumbnail(int pImageIndex, int pThumbnailIndex) throws IOException {
        // TODO: Thumbnail listeners...
        PSDThumbnail thumbnail = getThumbnailResource(pImageIndex, pThumbnailIndex);

        // TODO: Defer decoding
        // TODO: It's possible to attach listeners to the ImageIO reader delegate... But do we really care?
        processThumbnailStarted(pImageIndex, pThumbnailIndex);
        processThumbnailComplete();

        return thumbnail.getThumbnail();
    }

    /// Functional testing
    public static void main(final String[] pArgs) throws IOException {
        int subsampleFactor = 1;
        Rectangle sourceRegion = null;

        int idx = 0;
        while (pArgs[idx].charAt(0) == '-') {
            if (pArgs[idx].equals("-s")) {
                subsampleFactor = Integer.parseInt(pArgs[++idx]);
            }
            else if (pArgs[idx].equals("-r")) {
                int xw = Integer.parseInt(pArgs[++idx]);
                int yh = Integer.parseInt(pArgs[++idx]);

                try {
                    int w = Integer.parseInt(pArgs[idx + 1]);
                    int h = Integer.parseInt(pArgs[idx + 2]);

                    idx += 2;

                    // x y w h
                    sourceRegion = new Rectangle(xw, yh, w, h);
                }
                catch (NumberFormatException e) {
                    // w h
                    sourceRegion = new Rectangle(xw, yh);
                }

                System.out.println("sourceRegion: " + sourceRegion);
            }
            else {
                System.err.println("Usage: java PSDImageReader [-s <subsample factor>] [-r [<x y>] <w h>] <image file>");
                System.exit(1);
            }

            idx++;
        }

        PSDImageReader imageReader = new PSDImageReader(null);

        File file = new File(pArgs[idx]);
        ImageInputStream stream = ImageIO.createImageInputStream(file);
        imageReader.setInput(stream);
        imageReader.readHeader();
        System.out.println("imageReader.mHeader: " + imageReader.mHeader);

        imageReader.readImageResources(true);
        System.out.println("imageReader.mImageResources: " + imageReader.mImageResources);

        imageReader.readLayerAndMaskInfo(true);
        System.out.println("imageReader.mLayerInfo: " + imageReader.mLayerInfo);
        System.out.println("imageReader.mGlobalLayerMask: " + imageReader.mGlobalLayerMask);

        if (imageReader.hasThumbnails(0)) {
            int thumbnails = imageReader.getNumThumbnails(0);
            for (int i = 0; i < thumbnails; i++) {
                showIt(imageReader.readThumbnail(0, i), String.format("Thumbnail %d", i));                
            }
        }

        long start = System.currentTimeMillis();

        ImageReadParam param = imageReader.getDefaultReadParam();

        if (sourceRegion != null) {
            param.setSourceRegion(sourceRegion);
        }

        if (subsampleFactor > 1) {
            param.setSourceSubsampling(subsampleFactor, subsampleFactor, 0, 0);
        }

        BufferedImage image = imageReader.read(0, param);
        System.out.println("time: " + (System.currentTimeMillis() - start));
        System.out.println("image: " + image);

        if (image.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_CMYK) {
            try {
                ColorConvertOp op = new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_sRGB), null);
                image = op.filter(image, new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_4BYTE_ABGR_PRE));
            }
            catch (Exception e) {
                e.printStackTrace();
                image = ImageUtil.accelerate(image);
            }
            System.out.println("time: " + (System.currentTimeMillis() - start));
            System.out.println("image: " + image);
        }

        showIt(image, file.getName());
    }
}