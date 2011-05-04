/**
 * Acceso Inteligente
 *
 * Copyright (C) 2010-2011 Fundación Ciudadano Inteligente
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.accesointeligente.server.robots;

import org.accesointeligente.model.*;
import org.accesointeligente.server.ApplicationProperties;
import org.accesointeligente.server.Emailer;
import org.accesointeligente.server.HibernateUtil;
import org.accesointeligente.shared.FileType;
import org.accesointeligente.shared.RequestStatus;

import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;

import org.apache.commons.io.FileUtils;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.hibernate.Criteria;
import org.hibernate.FetchMode;
import org.hibernate.criterion.Restrictions;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.*;
import javax.mail.internet.MimeUtility;
import javax.mail.search.FlagTerm;

public class ResponseChecker {
	private Properties props;
	private Session session;
	private Store store;
	private Pattern pattern = Pattern.compile(".*([A-Z]{2}[0-9]{3}[A-Z]-{0,1}[0-9]{7}).*");
	private List<Attachment> attachments;
	private String remoteIdentifier;
	private String messageBody;

	public ResponseChecker() {
		props = System.getProperties();
		props.setProperty("mail.store.protocol", "imaps");
	}

	public void connectAndCheck() {
		if (ApplicationProperties.getProperty("email.server") == null || ApplicationProperties.getProperty("email.user") == null ||
				ApplicationProperties.getProperty("email.password") == null || ApplicationProperties.getProperty("email.folder") == null ||
				ApplicationProperties.getProperty("attachment.directory") == null || ApplicationProperties.getProperty("attachment.baseurl") == null) {
			System.err.println("ResponseChecker: No estan definidas las propiedades!");
			return;
		}

		org.hibernate.Session hibernate = null;

		try {
			session = Session.getInstance(props, null);
			store = session.getStore("imaps");
			store.connect(ApplicationProperties.getProperty("email.server"), ApplicationProperties.getProperty("email.user"), ApplicationProperties.getProperty("email.password"));

			Folder folder = store.getFolder(ApplicationProperties.getProperty("email.folder"));
			folder.open(Folder.READ_WRITE);

			for (Message message : folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false))) {
				System.out.println(message.getFrom()[0] + "\t" + message.getSubject());
				remoteIdentifier = null;
				messageBody = null;
				attachments = new ArrayList<Attachment>();

				Matcher matcher = pattern.matcher(message.getSubject());

				if (matcher.matches()) {
					remoteIdentifier = matcher.group(1);
				}

				Multipart mp = (Multipart) message.getContent();

				for (int i = 0, n = mp.getCount(); i < n; i++) {
					Part part = mp.getBodyPart(i);
					processPart(part);
				}

				if (remoteIdentifier == null) {
					hibernate = HibernateUtil.getSession();
					hibernate.beginTransaction();

					for (Attachment attachment : attachments) {
						hibernate.delete(attachment);
					}

					hibernate.getTransaction().commit();
					continue;
				} else {
					hibernate = HibernateUtil.getSession();
					hibernate.beginTransaction();

					Criteria criteria = hibernate.createCriteria(Request.class);
					criteria.add(Restrictions.eq("remoteIdentifier", remoteIdentifier));
					criteria.setFetchMode("user", FetchMode.JOIN);
					Request request = (Request) criteria.uniqueResult();
					hibernate.getTransaction().commit();

					if (request == null) {
						hibernate = HibernateUtil.getSession();
						hibernate.beginTransaction();

						for (Attachment attachment : attachments) {
							hibernate.delete(attachment);
						}

						hibernate.getTransaction().commit();
						continue;
					}

					hibernate = HibernateUtil.getSession();
					hibernate.beginTransaction();

					request.setStatus(RequestStatus.CLOSED);
					request.setResponseDate(new Date());
					hibernate.update(request);

					Response response = new Response();
					response.setSender(message.getFrom()[0].toString());
					response.setDate(new Date());
					response.setRequest(request);
					response.setInformation(messageBody);
					hibernate.save(response);

					for (Attachment attachment : attachments) {
						attachment.setResponse(response);
						hibernate.update(attachment);
					}

					hibernate.getTransaction().commit();

					// TODO: send permalink to request
					User user = request.getUser();

					Emailer emailer = new Emailer();
					emailer.setRecipient(user.getEmail());
					emailer.setSubject(ApplicationProperties.getProperty("email.response.arrived.subject"));
					emailer.setBody(String.format(ApplicationProperties.getProperty("email.response.arrived.body"), user.getFirstName(), request.getTitle()) + ApplicationProperties.getProperty("email.signature"));
					emailer.connectAndSend();
				}
			}
		} catch (Exception e) {
			if (hibernate != null && hibernate.isOpen() && hibernate.getTransaction().isActive()) {
				hibernate.getTransaction().rollback();
			}

			e.printStackTrace(System.err);
		}
	}

	private void processPart(Part part) throws Exception {
		String disposition = part.getDisposition();
		Matcher matcher;
		org.hibernate.Session hibernate;

		if (disposition != null && disposition.equalsIgnoreCase(Part.ATTACHMENT)) {
			FileType filetype = null;

			// TODO: other formats
			if (part.isMimeType("text/plain")) {
				String text = (String) part.getContent();

				if (remoteIdentifier == null) {
					StringTokenizer tokenizer = new StringTokenizer(text);

					while (tokenizer.hasMoreTokens()) {
						matcher = pattern.matcher(tokenizer.nextToken());

						if (matcher.matches()) {
							remoteIdentifier = matcher.group(1);
							break;
						}
					}
				}

				messageBody = text;
				return;
			} else if (part.isMimeType("text/html")) {
				String text = (String) part.getContent();

				if (remoteIdentifier == null) {
					StringTokenizer tokenizer = new StringTokenizer(text);

					while (tokenizer.hasMoreTokens()) {
						matcher = pattern.matcher(tokenizer.nextToken());

						if (matcher.matches()) {
							remoteIdentifier = matcher.group(1);
							break;
						}
					}
				}

				if (messageBody == null) {
					messageBody = text;
				}

				return;
			} else if (part.isMimeType("application/msword")) {
				filetype = FileType.DOC;
			} else if (part.isMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
				filetype = FileType.DOCX;
			} else if (part.isMimeType("application/pdf")) {
				filetype = FileType.PDF;
			} else {
				Matcher fileMatcher = Pattern.compile(".*\\.([A-Za-z0-9]+)$").matcher(MimeUtility.decodeText(part.getFileName()));

				if (fileMatcher.matches()) {
					try {
						filetype = Enum.valueOf(FileType.class, fileMatcher.group(1).toUpperCase());
					} catch (Exception e) {
						filetype = FileType.BIN;
					}
				} else {
					filetype = FileType.BIN;
				}
			}

			try {
				switch (filetype) {
					case DOC:
						if (remoteIdentifier == null) {
							WordExtractor extractor = new WordExtractor(part.getInputStream());
							StringTokenizer tokenizer = new StringTokenizer(extractor.getText());

							while (tokenizer.hasMoreTokens()) {
								matcher = pattern.matcher(tokenizer.nextToken());

								if (matcher.matches()) {
									remoteIdentifier = matcher.group(1);
									break;
								}
							}
						}
						break;
					case DOCX:
						if (remoteIdentifier == null) {
							XWPFWordExtractor extractor = new XWPFWordExtractor(new XWPFDocument(part.getInputStream()));
							StringTokenizer tokenizer = new StringTokenizer(extractor.getText());

							while (tokenizer.hasMoreTokens()) {
								matcher = pattern.matcher(tokenizer.nextToken());

								if (matcher.matches()) {
									remoteIdentifier = matcher.group(1);
									break;
								}
							}
						}
						break;
					case PDF:
						if (remoteIdentifier == null) {
							PdfReader reader = new PdfReader(part.getInputStream());

							for (int page = 1; page <= reader.getNumberOfPages(); page++) {
								if (remoteIdentifier != null) {
									break;
								}

								StringTokenizer tokenizer = new StringTokenizer(PdfTextExtractor.getTextFromPage(reader, page));

								while (tokenizer.hasMoreTokens()) {
									matcher = pattern.matcher(tokenizer.nextToken());

									if (matcher.matches()) {
										remoteIdentifier = matcher.group(1);
										reader.close();
										break;
									}
								}
							}
						}
						break;
					default:
				}
			} catch (Exception e) {
				System.err.println("Error procesando " + MimeUtility.decodeText(part.getFileName()));
				e.printStackTrace();
				throw e;
			}

			hibernate = HibernateUtil.getSession();
			hibernate.beginTransaction();

			Attachment attachment = new Attachment();
			hibernate.save(attachment);

			hibernate.getTransaction().commit();

			String directory = ApplicationProperties.getProperty("attachment.directory") + attachment.getId().toString();
			String baseUrl = ApplicationProperties.getProperty("attachment.baseurl") + attachment.getId().toString();

			String filename = MimeUtility.decodeText(part.getFileName());

			attachment.setName(filename);
			attachment.setType(filetype);
			attachment.setUrl(baseUrl + "/" + filename);

			try {
				File dir = new File(directory);
				dir.mkdir();
				FileUtils.copyInputStreamToFile(part.getInputStream(), new File(dir, filename));
			} catch (Exception e) {
				hibernate = HibernateUtil.getSession();
				hibernate.beginTransaction();
				hibernate.delete(attachment);
				hibernate.getTransaction().commit();
				System.err.println("Error saving " + directory + filename);
				e.printStackTrace();
				throw e;
			}

			hibernate = HibernateUtil.getSession();
			hibernate.beginTransaction();
			hibernate.update(attachment);
			hibernate.getTransaction().commit();
			attachments.add(attachment);
		} else {
			if (part.isMimeType("text/plain")) {
				String text = (String) part.getContent();

				if (remoteIdentifier == null) {
					StringTokenizer tokenizer = new StringTokenizer(text);

					while (tokenizer.hasMoreTokens()) {
						String token = tokenizer.nextToken();
						matcher = pattern.matcher(token);

						if (matcher.matches()) {
							remoteIdentifier = matcher.group(1);
							break;
						}
					}
				}

				messageBody = text;
				return;
			} else if (part.isMimeType("text/html")) {
				String text = (String) part.getContent();

				if (remoteIdentifier == null) {
					StringTokenizer tokenizer = new StringTokenizer(text);

					while (tokenizer.hasMoreTokens()) {
						matcher = pattern.matcher(tokenizer.nextToken());

						if (matcher.matches()) {
							remoteIdentifier = matcher.group(1);
							break;
						}
					}
				}

				if (messageBody == null) {
					messageBody = text;
				}

				return;
			} else if (part.isMimeType("multipart/*")) {
				Multipart mp = (Multipart) part.getContent();

				for (int i = 0, n = mp.getCount(); i < n; i++) {
					processPart(mp.getBodyPart(i));
				}
			}
		}
	}
}
