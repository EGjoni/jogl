/*
 * Copyright (c) 2003 Sun Microsystems, Inc. All Rights Reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 * - Redistribution of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * 
 * - Redistribution in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * 
 * Neither the name of Sun Microsystems, Inc. or the names of
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES,
 * INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN
 * MICROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO EVENT WILL SUN OR
 * ITS LICENSORS BE LIABLE FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR
 * DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL, INCIDENTAL OR PUNITIVE
 * DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY OF LIABILITY,
 * ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE, EVEN IF
 * SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 * 
 * You acknowledge that this software is not designed or intended for use
 * in the design, construction, operation or maintenance of any nuclear
 * facility.
 * 
 * Sun gratefully acknowledges that this software was originally authored
 * and developed by Kenneth Bradley Russell and Christopher John Kline.
 */

package net.java.games.jogl.impl.macosx;

import java.awt.Component;
import java.util.*;

import net.java.games.jogl.*;
import net.java.games.jogl.impl.*;

import java.security.*;

public class MacOSXOnscreenGLContext extends MacOSXGLContext {
  // Variables for lockSurface/unlockSurface
  private JAWT_DrawingSurface ds;
  private JAWT_DrawingSurfaceInfo dsi;
  private JAWT_MacOSXDrawingSurfaceInfo macosxdsi;
    
  // Indicates whether the component (if an onscreen context) has been
  // realized. Plausibly, before the component is realized the JAWT
  // should return an error or NULL object from some of its
  // operations; this appears to be the case on Win32 but is not true
  // at least with Sun's current X11 implementation (1.4.x), which
  // crashes with no other error reported if the DrawingSurfaceInfo is
  // fetched from a locked DrawingSurface during the validation as a
  // result of calling show() on the main thread. To work around this
  // we prevent any JAWT or OpenGL operations from being done until
  // addNotify() is called on the component.
  protected boolean realized;

  // Variables for pbuffer support
  List pbuffersToInstantiate = new ArrayList();

  // Workaround for instance of 4796548
  private boolean firstLock = true;

  public MacOSXOnscreenGLContext(Component component,
                                 GLCapabilities capabilities,
                                 GLCapabilitiesChooser chooser,
                                 GLContext shareWith) {
    super(component, capabilities, chooser, shareWith);
  }

  protected boolean isOffscreen() {
    return false;
  }
    
  public int getOffscreenContextReadBuffer() {
    throw new GLException("Should not call this");
  }
    
  public boolean offscreenImageNeedsVerticalFlip() {
    throw new GLException("Should not call this");
  }
    
  public boolean canCreatePbufferContext() {
    return true;
  }
    
  public GLContext createPbufferContext(GLCapabilities capabilities, int initialWidth, int initialHeight) {
    MacOSXPbufferGLContext ctx = new MacOSXPbufferGLContext(capabilities, initialWidth, initialHeight);
    ctx.setSynchronized(true);
    GLContextShareSet.registerSharing(this, ctx);
    pbuffersToInstantiate.add(ctx);
    return ctx;
  }
    
  public void bindPbufferToTexture() {
    throw new GLException("Should not call this");
  }
    
  public void releasePbufferFromTexture() {
    throw new GLException("Should not call this");
  }
    
  public void setRealized() {
    realized = true;
  }

  protected int makeCurrentImpl() throws GLException {
    try {
      if (!realized) {
        return CONTEXT_NOT_CURRENT;
      }
      if (!lockSurface()) {
        return CONTEXT_NOT_CURRENT;
      }
      int ret = super.makeCurrentImpl();
      if ((ret == CONTEXT_CURRENT) ||
          (ret == CONTEXT_CURRENT_NEW)) {
        // Assume the canvas might have been resized or moved and tell the OpenGL
        // context to update itself. This used to be done only upon receiving a
        // reshape event but that doesn't appear to be sufficient. An experiment
        // was also done to add a HierarchyBoundsListener to the GLCanvas and
        // do this updating only upon reshape of this component or reshape or movement
        // of an ancestor, but this also wasn't sufficient and left garbage on the
        // screen in some situations.
        CGL.updateContext(nsContext, nsView);
        // Instantiate any pending pbuffers
        while (!pbuffersToInstantiate.isEmpty()) {
          MacOSXPbufferGLContext ctx =
            (MacOSXPbufferGLContext) pbuffersToInstantiate.remove(pbuffersToInstantiate.size() - 1);
          ctx.createPbuffer(nsView, nsContext);
        }
      } else {
        // View might not have been ready
        unlockSurface();
      }
      return ret;
    } catch (RuntimeException e) {
      try {
        unlockSurface();
      } catch (Exception e2) {
        // do nothing if unlockSurface throws
      }
      throw(e); 
    }
  }
    
  protected void releaseImpl() throws GLException {
    try {
      super.releaseImpl();
    } finally {
      unlockSurface();
    }
  }
    
  protected void destroyImpl() throws GLException {
    realized = false;
    super.destroyImpl();
  }

  public void swapBuffers() throws GLException {
    if (!CGL.flushBuffer(nsContext, nsView)) {
      throw new GLException("Error swapping buffers");
    }
  }
        
  private boolean lockSurface() throws GLException {
    if (nsView != 0) {
      throw new GLException("Surface already locked");
    }
                
    ds = getJAWT().GetDrawingSurface(component);
    if (ds == null) {
      // Widget not yet realized
      return false;
    }
        
    int res = ds.Lock();
    if ((res & JAWTFactory.JAWT_LOCK_ERROR) != 0) {
      throw new GLException("Unable to lock surface");
    }
        
    // See whether the surface changed and if so destroy the old
    // OpenGL nsContext so it will be recreated
    if ((res & JAWTFactory.JAWT_LOCK_SURFACE_CHANGED) != 0) {
      if (nsContext != 0) {
		//CGL.updateContextUnregister(nsContext, nsView, updater); // gznote: not thread safe yet!
        if (!CGL.deleteContext(nsContext, nsView)) {
          throw new GLException("Unable to delete old GL nsContext after surface changed");
        }
      }
    }
        
    if (firstLock) {
      AccessController.doPrivileged(new PrivilegedAction() {
          public Object run() {
            dsi = ds.GetDrawingSurfaceInfo();
            return null;
          }
        });
    } else {
      dsi = ds.GetDrawingSurfaceInfo();
    }
    if (dsi == null) {
      ds.Unlock();
      getJAWT().FreeDrawingSurface(ds);
      ds = null;
            
      // Widget not yet realized
      return false;
    }
        
    firstLock = false;

    macosxdsi = (JAWT_MacOSXDrawingSurfaceInfo) dsi.platformInfo();
    if (macosxdsi == null) {
      ds.FreeDrawingSurfaceInfo(dsi);
      ds.Unlock();
      getJAWT().FreeDrawingSurface(ds);
      ds = null;
      dsi = null;
                
      // Widget not yet realized
      return false;
    }
                        
    nsView = macosxdsi.cocoaViewRef();
    if (nsView == 0) {
      ds.FreeDrawingSurfaceInfo(dsi);
      ds.Unlock();
      getJAWT().FreeDrawingSurface(ds);
      ds = null;
      dsi = null;
      macosxdsi = null;
                
      // Widget not yet realized
      return false;
    }
        
    return true;
  }
    
  private void unlockSurface() throws GLException {
    if (nsView == 0) {
      throw new GLException("Surface already unlocked");
    }
        
    ds.FreeDrawingSurfaceInfo(dsi);
    ds.Unlock();
    getJAWT().FreeDrawingSurface(ds);
    ds = null;
    dsi = null;
    macosxdsi = null;
    nsView = 0;
  }
}
