/**
 * Copyright 2011 JogAmp Community. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY JogAmp Community ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JogAmp Community OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of JogAmp Community.
 */
package jogamp.graph.font.typecast;

import jogamp.graph.geom.plane.AffineTransform;
import jogamp.graph.geom.plane.Path2D;

import com.jogamp.graph.font.Font;
import com.jogamp.graph.geom.PointTex;
import com.jogamp.graph.geom.Point.Factory;

import net.java.dev.typecast.ot.Point;
import net.java.dev.typecast.ot.OTGlyph;

/**
 * Factory to build a {@link com.jogamp.graph.geom.Path2D Path2D} from 
 * {@link net.java.dev.typecast.ot.OTGlyph Glyph}s. 
 */
public class TypecastRenderer {

	public static void getOutline(Factory<? extends PointTex> factory, TypecastFont font, 
			                      String string, AffineTransform transform, Path2D[] p)
	{		
		if (string == null) {
			return;
		}
		Font.Metrics metrics = font.getMetrics();
		float advanceTotal = 0;
		float lineGap = metrics.getLineGap() ;
		float ascent = metrics.getAscent() ;
		float descent = metrics.getDescent() ;
		if (transform == null) {
			transform = new AffineTransform(factory);
		}
		AffineTransform t = new AffineTransform(factory);

		float advanceY = lineGap - descent + ascent;
		float y = 0;
		for (int i=0; i<string.length(); i++)
		{
			p[i] = new Path2D();
			p[i].reset();
			t.setTransform(transform);
			char character = string.charAt(i);
			if (character == '\n') {
				y -= advanceY;
				advanceTotal = 0;
				continue;
			}
			TypecastGlyph glyph = (TypecastGlyph) font.getGlyph(character);
			Path2D gp = glyph.getNormalPath();
			float scale = metrics.getScale();
			t.translate(advanceTotal, y);
			t.scale(scale, scale);
			p[i].append(gp.iterator(t), false);
			advanceTotal += glyph.getAdvanceForPixelSize(font.getSize(), true); 
		}
	}
	
    /**
     * Build a {@link com.jogamp.graph.geom.Path2D Path2D} from a
     * {@link net.java.dev.typecast.ot.OTGlyph Glyph}.  This glyph path can then
     * be transformed and rendered.
     */
    public static Path2D buildPath(OTGlyph glyph) {
        
        if (glyph == null) {
            return null;
        }

        Path2D glyphPath = new Path2D();

        // Iterate through all of the points in the glyph.  Each time we find a
        // contour end point, add the point range to the path.
        int firstIndex = 0;
        int count = 0;
        for (int i = 0; i < glyph.getPointCount(); i++) {
            count++;
            if (glyph.getPoint(i).endOfContour) {
                addContourToPath(glyphPath, glyph, firstIndex, count);
                firstIndex = i + 1;
                count = 0;
            }
        }
        return glyphPath;
    }
    
    private static void addContourToPath(Path2D gp, OTGlyph glyph, int startIndex, int count) {
        int offset = 0;
        while (offset < count) {
            Point point = glyph.getPoint(startIndex + offset%count);
            Point point_plus1 = glyph.getPoint(startIndex + (offset+1)%count);
            Point point_plus2 = glyph.getPoint(startIndex + (offset+2)%count);
        	if(offset == 0)
            {
                gp.moveTo(point.x, -point.y);
            }
        	
        	if (point.onCurve) {
	        	if (point_plus1.onCurve) {
	                // s = new Line2D.Float(point.x, -point.y, point_plus1.x, -point_plus1.y);
	                gp.lineTo( point_plus1.x, -point_plus1.y );
	                offset++;	                
	            } else {
	            	if (point_plus2.onCurve) {
		                // s = new QuadCurve2D.Float( point.x, -point.y, point_plus1.x, -point_plus1.y, point_plus2.x, -point_plus2.y);
		                gp.quadTo(point_plus1.x, -point_plus1.y, point_plus2.x, -point_plus2.y);
		                offset+=2;	                
		            } else {
		                // s = new QuadCurve2D.Float(point.x,-point.y,point_plus1.x,-point_plus1.y,
		            	//                           midValue(point_plus1.x, point_plus2.x), -midValue(point_plus1.y, point_plus2.y));
		                gp.quadTo(point_plus1.x, -point_plus1.y, midValue(point_plus1.x, point_plus2.x), -midValue(point_plus1.y, point_plus2.y));
		                offset+=2;
		            }
	            }
        	} else {
        		if (point_plus1.onCurve) {
	                // s = new QuadCurve2D.Float(midValue(point_minus1.x, point.x), -midValue(point_minus1.y, point.y),
	                //                           point.x, -point.y, point_plus1.x, -point_plus1.y);
	                //gp.curve3(point_plus1.x, -point_plus1.y, point.x, -point.y);
	                gp.quadTo(point.x, -point.y, point_plus1.x, -point_plus1.y);
	                offset++;
    				
    			} else {
	                // s = new QuadCurve2D.Float(midValue(point_minus1.x, point.x), -midValue(point_minus1.y, point.y), point.x, -point.y,
	                //                           midValue(point.x, point_plus1.x), -midValue(point.y, point_plus1.y));
	                //gp.curve3(midValue(point.x, point_plus1.x), -midValue(point.y, point_plus1.y), point.x, -point.y);
	                gp.quadTo(point.x, -point.y, midValue(point.x, point_plus1.x), -midValue(point.y, point_plus1.y));
	                offset++;	                
	            }
        	}
        }
    }

    private static int midValue(int a, int b) {
        return a + (b - a)/2;
    }
}
