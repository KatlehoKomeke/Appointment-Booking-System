package za.co.fynbos.branchbook.shared.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React app's page for each of the app's own routes, so a customer can open, bookmark or
 * refresh a link such as {@code /appointments/APT-7K3M-9Q2X} (the link in the confirmation SMS) and
 * still get the app.
 *
 * <p>The routes are listed one by one rather than matched by a catch-all pattern. An unknown path
 * therefore still returns a real 404 instead of the app, and nothing under {@code /api} or {@code
 * /actuator} can ever be shadowed. A route added to the app in {@code web/src} must be added here
 * and to {@code SpaForwardingControllerTest}.
 */
@Controller
class SpaForwardingController {

  /**
   * Forwards a route to the app's single HTML page, where the browser-side router renders it.
   *
   * @return a server-side forward to the app's page, invisible to the browser
   */
  @GetMapping({"/book", "/manage", "/messages", "/privacy", "/appointments/{reference}"})
  String forwardToApp() {
    return "forward:/index.html";
  }
}
