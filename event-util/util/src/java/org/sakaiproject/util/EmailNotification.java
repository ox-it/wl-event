/**********************************************************************************
 * $URL$
 * $Id$
 ***********************************************************************************
 *
 * Copyright (c) 2003, 2004, 2005, 2006 The Sakai Foundation.
 * 
 * Licensed under the Educational Community License, Version 1.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at
 * 
 *      http://www.opensource.org/licenses/ecl1.php
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 *
 **********************************************************************************/

package org.sakaiproject.util;

import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.email.cover.DigestService;
import org.sakaiproject.email.cover.EmailService;
import org.sakaiproject.entity.api.EntityPropertyNotDefinedException;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.cover.EntityManager;
import org.sakaiproject.event.api.Event;
import org.sakaiproject.event.api.Notification;
import org.sakaiproject.event.api.NotificationAction;
import org.sakaiproject.event.cover.NotificationService;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.cover.SiteService;
import org.sakaiproject.time.cover.TimeService;
import org.sakaiproject.tool.cover.SessionManager;
import org.sakaiproject.user.api.Preferences;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.user.cover.PreferencesService;
import org.sakaiproject.user.cover.UserDirectoryService;
import org.w3c.dom.Element;

/**
 * <p>
 * EmailNotification is the notification action that handles the act of message (email) based notify, site related, with user preferences.
 * </p>
 * <p>
 * The following should be specified to extend the class:
 * <ul>
 * <li>getRecipients() - get a collection of Users to send the notification to</li>
 * <li>getHeaders() - form the complete message headers (like from: to: reply-to: date: subject: etc). from: and to: are for display only</li>
 * <li>htmlContent() - form the complete html message body (minus headers)</li>
 * <li>plainTextContent() - form the complete plain text message body (minus headers)</li>
 * <li>getTag() - the part of the body at the end that identifies the list</li>
 * </ul>
 * </p>
 * <p>
 * getClone() should also be extended to clone the proper type of object.
 * </p>
 */
public class EmailNotification implements NotificationAction
{
	private final String MULTIPART_BOUNDARY = "======sakai-multi-part-boundary======";
	private final String BOUNDARY_LINE = "\n\n--"+MULTIPART_BOUNDARY+"\n";
	private final String TERMINATION_LINE = "\n\n--"+MULTIPART_BOUNDARY+"--\n\n";

	private final String MIME_ADVISORY = "This message is for MIME-compliant mail readers.";
	
	/** The related site id. */
	protected String m_siteId = null;

	/**
	 * Construct.
	 */
	public EmailNotification()
	{
	}

	/**
	 * Construct.
	 * 
	 * @param siteId
	 *        The related site id.
	 */
	public EmailNotification(String siteId)
	{
		m_siteId = siteId;
	}

	/**
	 * Set from an xml element.
	 * 
	 * @param el
	 *        The xml element.
	 */
	public void set(Element el)
	{
		m_siteId = StringUtil.trimToNull(el.getAttribute("site"));
	}

	/**
	 * Set from another.
	 * 
	 * @param other
	 *        The other to copy.
	 */
	public void set(NotificationAction other)
	{
		EmailNotification eOther = (EmailNotification) other;
		m_siteId = eOther.m_siteId;
	}

	/**
	 * Make a new one like me.
	 * 
	 * @return A new action just like me.
	 */
	public NotificationAction getClone()
	{
		EmailNotification clone = makeEmailNotification();
		clone.set(this);

		return clone;
	}

	protected EmailNotification makeEmailNotification() {
		return null;
	}

	/**
	 * Fill this xml element with the attributes.
	 * 
	 * @param el
	 *        The xml element.
	 */
	public void toXml(Element el)
	{
		if (m_siteId != null) el.setAttribute("site", m_siteId);
	}

	/**
	 * Do the notification.
	 * 
	 * @param notification
	 *        The notification responding to the event.
	 * @param event
	 *        The event that matched criteria to cause the notification.
	 */
	public void notify(Notification notification, Event event)
	{
		// ignore events marked for no notification
		if (event.getPriority() == NotificationService.NOTI_NONE) return;

		// get the list of potential recipients
		List recipients = getRecipients(event);

		// filter to actual immediate recipients
		List immediate = immediateRecipients(recipients, notification, event);

		// and the list of digest recipients
		List digest = digestRecipients(recipients, notification, event);

		// we may be done
		if ((immediate.size() == 0) && (digest.size() == 0)) return;

		// get the email elements - headers (including to: from: subject: date: and anything else we want in the message) and body
		List headers = getHeaders(event);

		if ( "true".equals(ServerConfigurationService.getString("email.precedence.bulk", "false")) )  
		{
			String bulkFlag = "Precedence: bulk";
			// Add presedence:bulk to mark notifs as a type of bulk mail
			// This allows some email systems to deal with it correctly,
			// e.g. they won't send OOO replies / vacation messages
			headers.add(bulkFlag);
		}

		// for the immediates
		if (immediate.size() > 0)
		{
			// get formatted message (including tag)
			String message = getMessage(event);
			
			// send message to immediates, with headers
			EmailService.sendToUsers(immediate, headers, message);
		}

		// for the digesters
		if (digest.size() > 0)
		{
			String message = plainTextContent(event);

			// modify the message to add header lines (we don't add a tag for each message, the digest adds a single one when sent)
			StringBuilder messageForDigest = new StringBuilder();

			String item = findHeader("From", headers);
			if (item != null) messageForDigest.append(item+"\n");

			item = findHeader("Date", headers);
			if (item != null)
			{
				messageForDigest.append(item+"\n");
			}
			else
			{
				messageForDigest.append("Date: " + TimeService.newTime().toStringLocalFullZ() + "\n");
			}

			item = findHeader("To", headers);
			if (item != null) messageForDigest.append(item+"\n");

			item = findHeader("Cc", headers);
			if (item != null) messageForDigest.append(item+"\n");

			item = findHeader("Subject", headers);
			if (item != null) messageForDigest.append(item+"\n");

			// and the body
			messageForDigest.append("\n");
			messageForDigest.append(message);

			// digest the message to each user
			for (Iterator iDigests = digest.iterator(); iDigests.hasNext();)
			{
				User user = (User) iDigests.next();
				DigestService.digest(user.getId(), findHeaderValue("Subject", headers), messageForDigest.toString());
			}
		}
	}

	/**
	 * Get the message for the email.
	 * 
	 * @param event
	 *        The event that matched criteria to cause the notification.
	 * @return the message for the email.
	 */
	protected String getMessage(Event event)
	{	
		// get a site title: use either the configured site, or if not configured, the site (context) of the resource
		Reference ref = EntityManager.newReference(event.getResource());
		String title = (getSite() != null) ? getSite() : ref.getContext();
		try
		{
			Site site = SiteService.getSite(title);
			title = site.getTitle();
		}
		catch (Exception ignore) {}

		StringBuilder message = new StringBuilder();
		message.append(MIME_ADVISORY);
		
		message.append(BOUNDARY_LINE);
		message.append(plainTextHeaders());
		message.append(plainTextContent(event));
		message.append( getTag(title, false) );
		
		message.append(BOUNDARY_LINE);
		message.append(htmlHeaders());
		message.append(htmlPreamble(event));
		message.append(htmlContent(event));
		message.append( getTag(title, true) );
		message.append(htmlEnd());
		
		message.append(TERMINATION_LINE);
		return message.toString();
	}

	protected String plainTextHeaders() {
		return "Content-Type: text/plain\n\n";
	}
	
	protected String plainTextContent(Event event) {
		return null;
	}
	
	protected String htmlHeaders() {
		return "Content-Type: text/html\n\n";
	}
	
	protected String htmlPreamble(Event event) {
		StringBuilder buf = new StringBuilder();
		buf.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\"\n");
		buf.append("    \"http://www.w3.org/TR/html4/loose.dtd\">\n");
		buf.append("<html>");
		buf.append("  <head><title>");
		buf.append(getSubject(event));
		buf.append("</title></head>");
		buf.append("  <body>");
		return buf.toString();
	}
	
	protected String htmlContent(Event event) {
		return Web.encodeUrlsAsHtml(FormattedText.escapeHtml(plainTextContent(event),true));
	}
	
	protected String htmlEnd() {
		return "  </body></html>";
	}

	/**
	 * Get the message tag, the text to display at the bottom of the message.
	 * 
	 * @param title The title string
	 * @param shouldUseHtml if true, use html not plain text encoding in message
	 * @return The message tag.
	 */
	protected String getTag(String title, boolean shouldUseHtml)
	{
		return "";
	}

	/**
	 * Get headers for the email (List of String, full header lines) - including Subject: Date: To: From: if appropriate, as well as any others
	 * 
	 * @param event
	 *        The event that matched criteria to cause the notification.
	 * @return the additional headers for the email.
	 */
	protected List<String> getHeaders(Event event)
	{
		List<String> rv = new Vector<String>();
		
		rv.add("MIME-Version: 1.0");
		rv.add("Content-Type: multipart/alternative; boundary=\""+MULTIPART_BOUNDARY+"\"");
		
		return rv;
	}

	/**
	 * Get the list of User objects who are eligible to receive the notification email.
	 * 
	 * @param event
	 *        The event that matched criteria to cause the notification.
	 * @return the list of User objects who are eligible to receive the notification email.
	 */
	protected List getRecipients(Event event)
	{
		return new Vector();
	}

	/**
	 * Get the site id this notification is related to.
	 * 
	 * @return The site id this notification is related to.
	 */
	protected String getSite()
	{
		return m_siteId;
	}

	/**
	 * Filter the recipients Users into the list of those who get this one immediately. Combine the event's notification priority with the user's notification profile.
	 * 
	 * @param recipients
	 *        The List (User) of potential recipients.
	 * @param notification
	 *        The notification responding to the event.
	 * @param event
	 *        The event that matched criteria to cause the notification.
	 * @return The List (User) of immediate recipients.
	 */
	protected List immediateRecipients(List recipients, Notification notification, Event event)
	{
		int priority = event.getPriority();

		// required notification is sent to all
		if (priority == NotificationService.NOTI_REQUIRED)
		{
			return recipients;
		}

		List rv = new Vector();
		for (Iterator iUsers = recipients.iterator(); iUsers.hasNext();)
		{
			User user = (User) iUsers.next();

			// get the user's priority preference for this event
			int option = getOption(user, notification, event);

			// if immediate is the option, or there is no option, select this user
			// Note: required and none priority are already handled, so we know it's optional here.
			if (isImmediateDeliveryOption(option, notification))
			{
				rv.add(user);
			}
		}

		return rv;
	}

	/**
	 * Filter the preference option based on the notification resource type
	 * 
	 * @param option
	 *        The preference option.
	 * @param notification
	 *        The notification responding to the event.
	 * @return A boolean value which tells if the User is one of immediate recipients.
	 */
	protected boolean isImmediateDeliveryOption(int option, Notification notification)
	{
		if (option == NotificationService.PREF_IMMEDIATE)
		{
			return true;
		}
		else
		{
			if (option == NotificationService.PREF_NONE)
			{
				String type = EntityManager.newReference(notification.getResourceFilter()).getType();
				if (type != null)
				{
					if (type.equals("org.sakaiproject.mailarchive.api.MailArchiveService"))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Filter the recipients Users into the list of those who get this one by digest. Combine the event's notification priority with the user's notification profile.
	 * 
	 * @param recipients
	 *        The List (User) of potential recipients.
	 * @param notification
	 *        The notification responding to the event.
	 * @param event
	 *        The event that matched criteria to cause the notification.
	 * @return The List (User) of digest recipients.
	 */
	protected List digestRecipients(List recipients, Notification notification, Event event)
	{
		List rv = new Vector();

		int priority = event.getPriority();

		// priority notification is sent to all (i.e. no digests)
		if (priority == NotificationService.NOTI_REQUIRED)
		{
			return rv;
		}

		for (Iterator iUsers = recipients.iterator(); iUsers.hasNext();)
		{
			User user = (User) iUsers.next();

			// get the user's priority preference for this event
			int option = getOption(user, notification, event);

			// if digest is the option, select this user
			if (option == NotificationService.PREF_DIGEST)
			{
				rv.add(user);
			}
		}

		return rv;
	}

	/**
	 * Get the user's notification option for this... one of the NotificationService's PREF_ settings
	 */
	protected int getOption(User user, Notification notification, Event event)
	{
		String priStr = Integer.toString(event.getPriority());

		Preferences prefs = PreferencesService.getPreferences(user.getId());

		// get the user's preference for this notification
		ResourceProperties props = prefs.getProperties(NotificationService.PREFS_NOTI + notification.getId());
		try
		{
			int option = (int) props.getLongProperty(priStr);
			if (option != NotificationService.PREF_NONE) return option;
		}
		catch (Throwable ignore)
		{
		}

		// try the preference for the site from which resources are being watched for this notification
		// Note: the getSite() is who is notified, not what we are watching; that's based on the notification filter -ggolden
		String siteId = EntityManager.newReference(notification.getResourceFilter()).getContext();
		if (siteId != null)
		{
			props = prefs.getProperties(NotificationService.PREFS_SITE + siteId);
			try
			{
				int option = (int) props.getLongProperty(priStr);
				if (option != NotificationService.PREF_NONE) return option;
			}
			catch (Throwable ignore)
			{
			}
		}

		// try the default
		props = prefs.getProperties(NotificationService.PREFS_DEFAULT);
		try
		{
			int option = (int) props.getLongProperty(priStr);
			if (option != NotificationService.PREF_NONE) return option;
		}
		catch (Throwable ignore)
		{
		}

		// try the preference for the resource type service responsibile for resources of this notification
		String type = EntityManager.newReference(notification.getResourceFilter()).getType();
		if (type != null)
		{
			props = prefs.getProperties(NotificationService.PREFS_TYPE + type);
			try
			{
				int option = (int) props.getLongProperty(Integer.toString(NotificationService.NOTI_OPTIONAL));
				if (option != NotificationService.PREF_NONE) return option;
			}
			catch (EntityPropertyNotDefinedException e)
			{
				return NotificationService.PREF_IMMEDIATE;
			}
			catch (Throwable ignore)
			{
			}
		}

		// nothing defined...
		return NotificationService.PREF_NONE;
	}

	/**
	 * Find the header line that begins with the header parameter
	 * 
	 * @param header
	 *        The header to find.
	 * @param headers
	 *        The list of full header lines.
	 * @return The header line found or null if not found.
	 */
	protected String findHeader(String header, List headers)
	{
		for (Iterator i = headers.iterator(); i.hasNext();)
		{
			String h = (String) i.next();
			if (h.startsWith(header)) return h;
		}

		return null;
	}

	/**
	 * Find the header value whose name matches with the header parameter
	 * 
	 * @param header
	 *        The header to find.
	 * @param headers
	 *        The list of full header lines.
	 * @return The header line found or null if not found.
	 */
	protected String findHeaderValue(String header, List headers)
	{
		String line = findHeader(header, headers);
		if (line == null) return null;

		String value = line.substring(header.length() + 2);
		return value;
	}

	/**
	 * Format a From: respecting the notification service replyable configuration
	 * 
	 * @param event
	 * @return
	 */
	protected String getFrom(Event event)
	{
		if (NotificationService.isNotificationFromReplyable())
		{
			// from user display name <email>
			return "From: " + getFromEventUser(event);
		}
		else
		{
			// from the general service, no reply
			return "From: " + getFromService();
		}
	}

	/**
	 * Format a from address from the service, no reply.
	 * 
	 * @return a from address from the service, no reply.
	 */
	protected String getFromService()
	{
		return "\"" + ServerConfigurationService.getString("ui.service", "Sakai") + "\"<no-reply@"
				+ ServerConfigurationService.getServerName() + ">";
	}

	/**
	 * Format the from user email address based on the user generating the event (current user).
	 * 
	 * @param event
	 *        The event that matched criteria to cause the notification.
	 * @return the from user email address based on the user generating the event.
	 */
	protected String getFromEventUser(Event event)
	{
		String userDisplay = null;
		String userEmail = null;

		String userId = SessionManager.getCurrentSessionUserId();
		if (userId != null)
		{
			try
			{
				User u = UserDirectoryService.getUser(userId);
				userDisplay = u.getDisplayName();
				userEmail = u.getEmail();
				if ((userEmail != null) && (userEmail.trim().length()) == 0) userEmail = null;
			}
			catch (UserNotDefinedException e)
			{
			}
		}

		// some fallback positions
		if (userEmail == null) userEmail = "no-reply@" + ServerConfigurationService.getServerName();
		if (userDisplay == null) userDisplay = ServerConfigurationService.getString("ui.service", "Sakai");

		return "\"" + userDisplay + "\" <" + userEmail + ">";
	}
	
	/**
	 * Get the subject for the email.
	 * 
	 * @param event
	 *        The event that matched criteria to cause the notification.
	 * @return the subject for the email.
	 */
	protected String getSubject(Event event)
	{
		return findHeaderValue("Subject", getHeaders(event));
	}
	
}
