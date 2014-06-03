/*******************************************************************************
 * Copyright (c) 2014 Liviu Ionescu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Liviu Ionescu - initial implementation.
 *******************************************************************************/

package ilg.gnuarmeclipse.packs.handlers;

import ilg.gnuarmeclipse.packs.Activator;
import ilg.gnuarmeclipse.packs.PacksStorage;
import ilg.gnuarmeclipse.packs.Repos;
import ilg.gnuarmeclipse.packs.TreeNode;
import ilg.gnuarmeclipse.packs.Utils;
import ilg.gnuarmeclipse.packs.cmsis.Index;
import ilg.gnuarmeclipse.packs.cmsis.PdscParser;
import ilg.gnuarmeclipse.packs.xcdl.ContentSerialiser;

import java.io.FileNotFoundException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * Our sample handler extends AbstractHandler, an IHandler base class.
 * 
 * @see org.eclipse.core.commands.IHandler
 * @see org.eclipse.core.commands.AbstractHandler
 */
public class RefreshHandler extends AbstractHandler {

	private MessageConsoleStream m_out;
	private boolean m_running;

	private Repos m_repos;
	private PacksStorage m_storage;

	/**
	 * The constructor.
	 */
	public RefreshHandler() {
		System.out.println("RefreshHandler()");
		m_running = false;

		m_out = Activator.getConsoleOut();

		m_repos = Repos.getInstance();
		m_storage = PacksStorage.getInstance();
	}

	/**
	 * the command has been executed, so extract extract the needed information
	 * from the application context.
	 */
	public Object execute(ExecutionEvent event) throws ExecutionException {

		Job job = new Job("Refresh Packs") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				return myRun(monitor);
			}
		};

		job.schedule();
		return null;
	}

	// ------------------------------------------------------------------------

	private IStatus myRun(IProgressMonitor monitor) {

		if (m_running)
			return Status.CANCEL_STATUS;

		m_running = true;
		m_out.println("Refresh packs started.");

		int workUnits = 0;

		try {

			// keys: { "type", "url" }
			List<Map<String, Object>> reposList = m_repos.getList();

			for (Map<String, Object> repo : reposList) {

				if (monitor.isCanceled()) {
					break;
				}

				String type = (String) repo.get("type");
				String indexUrl = (String) repo.get("url");
				if (Repos.CMSIS_PACK_TYPE.equals(type)) {

					// String[] { url, name, version }
					List<String[]> list = new LinkedList<String[]>();

					// collect all pdsc references in this site
					readCmsisIndex(indexUrl, list);
					repo.put("list", list);
					workUnits += list.size();

				} else {
					m_out.println(type + " not recognised.");
				}
			}

			// Set total number of work units to the number of pdsc files
			monitor.beginTask("Refresh packs", workUnits);

			TreeNode tree = new TreeNode("root");

			PdscParser parser = new PdscParser();
			parser.setIsBrief(true);

			for (Map<String, Object> repo : reposList) {
				if (!repo.containsKey("list"))
					continue;

				// String[] { url, name, version }
				@SuppressWarnings("unchecked")
				List<String[]> list = (List<String[]>) repo.get("list");
				if (list.isEmpty())
					break;

				String repoUrl = (String) repo.get("url");
				TreeNode contentRoot = new TreeNode(TreeNode.REPOSITORY_TYPE);

				String domainName = m_repos.getDomaninNameFromUrl(repoUrl);
				domainName = Utils.capitalize(domainName);

				contentRoot.setName(domainName);
				contentRoot.setDescription(domainName
						+ " CMSIS packs repository");

				contentRoot.putProperty(TreeNode.PROPERTY.TYPE, "cmsis.repo");
				contentRoot.putProperty(TreeNode.PROPERTY.REPO_URL, repoUrl);
				contentRoot.putProperty(TreeNode.PROPERTY.GENERATOR,
						"GNU ARM Eclipse Plug-ins");

				DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
				dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
				Calendar cal = Calendar.getInstance();
				contentRoot.putProperty(TreeNode.PROPERTY.DATE,
						dateFormat.format(cal.getTime()));

				for (String[] pdsc : list) {

					if (monitor.isCanceled()) {
						break;
					}

					// Make url always end in '/'
					String pdscUrl = Utils.cosmetiseUrl(pdsc[0]);
					String pdscName = pdsc[1];
					String pdscVersion = pdsc[2];

					monitor.subTask(pdscName);

					parser.parseXml(new URL(pdscUrl + pdscName));
					parser.parsePdscBrief(pdscName, pdscVersion, tree);

					parser.parsePdscContent(pdscName, pdscVersion, contentRoot);

					// One more unit completed
					monitor.worked(1);
				}

				String fileName = m_repos.getRepoContentXmlFromUrl(repoUrl);

				ContentSerialiser serialiser = new ContentSerialiser();
				serialiser.serialise(contentRoot, fileName);

				m_out.println("content.xml written.");
			}

			if (monitor.isCanceled()) {
				m_out.println("Job cancelled.");
				m_running = false;
				return Status.CANCEL_STATUS;
			}

			// Write the tree to the cache.xml file in the packages folder
			m_storage.putCache(tree);
			m_out.println("Tree written.");

			m_storage.updateInstalled();
			m_out.println("Mark installed packs.");

		} catch (FileNotFoundException e) {
			m_out.println("Error: " + e.toString());
		} catch (Exception e) {
			Activator.log(e);
			m_out.println("Failed: " + e.toString());
		}

		Display.getDefault().asyncExec(new Runnable() {
			@Override
			public void run() {
				Activator.getPacksView().forceRefresh();
				Activator.getDevicesView().forceRefresh();
				Activator.getBoardsView().forceRefresh();
				Activator.getKeywordsView().forceRefresh();
			}
		});

		m_out.println("Refresh packs completed.");
		m_out.println();
		m_running = false;
		return Status.OK_STATUS;
	}

	private void readCmsisIndex(String indexUrl, List<String[]> pdscList) {

		m_out.println("Parsing \"" + indexUrl + "\"...");

		try {

			int count = Index.readIndex(indexUrl, pdscList);

			m_out.println("Contributed " + count + " pack(s).");

			return;

		} catch (FileNotFoundException e) {
			m_out.println("Failed: " + e.toString());
		} catch (Exception e) {
			Activator.log(e);
			m_out.println("Failed: " + e.toString());
		}

		return;

	}
}
