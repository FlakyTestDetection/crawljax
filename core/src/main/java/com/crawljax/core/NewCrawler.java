package com.crawljax.core;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Provider;

import org.openqa.selenium.ElementNotVisibleException;
import org.openqa.selenium.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crawljax.browser.EmbeddedBrowser;
import com.crawljax.condition.browserwaiter.WaitConditionChecker;
import com.crawljax.core.configuration.CrawlRules;
import com.crawljax.core.plugin.Plugins;
import com.crawljax.core.state.CrawlPath;
import com.crawljax.core.state.Element;
import com.crawljax.core.state.Eventable;
import com.crawljax.core.state.Eventable.EventType;
import com.crawljax.core.state.Identification;
import com.crawljax.core.state.StateMachine;
import com.crawljax.core.state.StateVertex;
import com.crawljax.di.ConfigurationModule.BaseUrl;
import com.crawljax.di.CoreModule.CandidateElementExtractorFactory;
import com.crawljax.di.CoreModule.FormHandlerFactory;
import com.crawljax.forms.FormHandler;
import com.crawljax.forms.FormInput;
import com.crawljax.oraclecomparator.StateComparator;
import com.crawljax.util.ElementResolver;
import com.crawljax.util.UrlUtils;

public class NewCrawler {

	private static final Logger LOG = LoggerFactory.getLogger(NewCrawler.class);

	private static enum ClickResult {
		CLONE_DETECTED, NEW_STATE, DOM_UNCHANGED, LEFT_DOMAIN
	}

	private final AtomicInteger crawlDepth = new AtomicInteger();
	private final EmbeddedBrowser browser;
	private final Provider<CrawlSession> session;
	private final StateComparator stateComparator;
	private final URL url;
	private final Plugins plugins;
	private final FormHandler formHandler;
	private final CrawlRules crawlRules;
	private final WaitConditionChecker waitConditionChecker;
	private final CandidateElementExtractor candidateExtractor;
	private final UnhandledCandidatActionCache candidateActionCache;

	private CrawlPath crawlpath;
	private StateMachine stateMachine;

	@Inject
	NewCrawler(EmbeddedBrowser browser, @BaseUrl URL url, Plugins plugins, CrawlRules crawlRules,
	        Provider<CrawlSession> session,
	        StateComparator stateComparator, UnhandledCandidatActionCache candidateActionCache,
	        FormHandlerFactory formHandlerFactory,
	        WaitConditionChecker waitConditionChecker,
	        CandidateElementExtractorFactory elementExtractor) {
		this.browser = browser;
		this.url = url;
		this.plugins = plugins;
		this.crawlRules = crawlRules;
		this.session = session;
		this.stateComparator = stateComparator;
		this.candidateActionCache = candidateActionCache;
		this.waitConditionChecker = waitConditionChecker;
		this.candidateExtractor = elementExtractor.newExtractor(browser);
		this.formHandler = formHandlerFactory.newFormHandler(browser);
	}

	/**
	 * Close the browser.
	 */
	public void close() {
		// TODO Auto-generated method stub

	}

	/**
	 * Reset the crawler to its initial state.
	 */
	public void reset() {
		if (crawlpath != null) {
			session.get().addCrawlPath(crawlpath);
		}
		CrawlSession sess = session.get();
		stateMachine =
		        new StateMachine(sess.getStateFlowGraph(), sess.getInitialState(),
		                crawlRules.getInvariants(), plugins);
		crawlpath = new CrawlPath();
		browser.goToUrl(url);
		plugins.runOnUrlLoadPlugins(browser);
	}

	/**
	 * @param crawlTask
	 *            The {@link CrawlTask} this {@link NewCrawler} should execute.
	 */
	public void execute(CrawlTask crawlTask) {
		follow(crawlpath.immutableCopy(false));
		// Store the currentState to be able to 'back-track' later.
		StateVertex originalState = stateMachine.getCurrentState();

		if (originalState.searchForCandidateElements(candidateExtractor)) {
			// Only execute the preStateCrawlingPlugins when it's the first time
			LOG.info("Starting preStateCrawlingPlugins...");
			List<CandidateElement> candidateElements =
			        originalState.getUnprocessedCandidateElements();
			plugins.runPreStateCrawlingPlugins(session.get(), candidateElements);
			// update crawlActions
			originalState.filterCandidateActions(candidateElements);
		}
		crawlThroughActions(originalState);
	}

	private void follow(CrawlPath path) throws CrawljaxException {
		StateVertex curState = session.get().getInitialState();

		for (Eventable clickable : path) {

			if (!candidateExtractor.checkCrawlCondition()) {
				LOG.debug("Crawl conditions not complete. Not following path");
				// TODO this is not correct probably.
				return;
			}

			LOG.debug("Backtracking by executing {} on element: {}", clickable.getEventType(),
			        clickable);

			stateMachine.changeState(clickable.getTargetStateVertex());

			curState = clickable.getTargetStateVertex();

			crawlpath.add(clickable);

			handleInputElements(clickable);

			if (this.fireEvent(clickable)) {

				int depth = crawlDepth.incrementAndGet();
				LOG.info("Crawl depth is now {}", depth);

				plugins.runOnRevisitStatePlugins(session.get(), curState);
			}

			if (!candidateExtractor.checkCrawlCondition()) {
				LOG.debug("Crawl conditions not complete. Not following path");
				// TODO this is not correct probably.
				return;
			}
		}
	}

	/**
	 * Enters the form data. First, the related input elements (if any) to the eventable are filled
	 * in and then it tries to fill in the remaining input elements.
	 * 
	 * @param eventable
	 *            the eventable element.
	 */
	private void handleInputElements(Eventable eventable) {
		CopyOnWriteArrayList<FormInput> formInputs = eventable.getRelatedFormInputs();

		for (FormInput formInput : formHandler.getFormInputs()) {
			if (!formInputs.contains(formInput)) {
				formInputs.add(formInput);
			}
		}
		formHandler.handleFormElements(formInputs);
	}

	/**
	 * Try to fire a given event on the Browser.
	 * 
	 * @param eventable
	 *            the eventable to fire
	 * @return true iff the event is fired
	 */
	private boolean fireEvent(Eventable eventable) {
		Eventable eventToFire = eventable;
		if (eventable.getIdentification().getHow().toString().equals("xpath")
		        && eventable.getRelatedFrame().equals("")) {
			eventToFire = resolveByXpath(eventable, eventToFire);
		}
		boolean isFired = false;
		try {
			isFired = browser.fireEventAndWait(eventToFire);
		} catch (ElementNotVisibleException | NoSuchElementException e) {
			if (crawlRules.isCrawlHiddenAnchors() && eventToFire.getElement() != null
			        && "A".equals(eventToFire.getElement().getTag())) {
				isFired = visitAnchorHrefIfPossible(eventToFire);
			} else {
				LOG.debug("Ignoring invisble element {}", eventToFire.getElement());
			}
		}

		LOG.debug("Event fired={} for eventable {}", isFired, eventable);

		if (isFired) {

			/*
			 * Let the controller execute its specified wait operation on the browser thread safe.
			 */
			waitConditionChecker.wait(browser);

			browser.closeOtherWindows();

			return true; // An event fired
		} else {
			/*
			 * Execute the OnFireEventFailedPlugins with the current crawlPath with the crawlPath
			 * removed 1 state to represent the path TO here.
			 */
			plugins.runOnFireEventFailedPlugins(eventable, crawlpath.immutableCopy(true));
			return false; // no event fired
		}
	}

	private Eventable resolveByXpath(Eventable eventable, Eventable eventToFire) {
		// The path in the page to the 'clickable' (link, div, span, etc)
		String xpath = eventable.getIdentification().getValue();

		// The type of event to execute on the 'clickable' like onClick,
		// mouseOver, hover, etc
		EventType eventType = eventable.getEventType();

		// Try to find a 'better' / 'quicker' xpath
		String newXPath = new ElementResolver(eventable, browser).resolve();
		if (newXPath != null && !xpath.equals(newXPath)) {
			LOG.debug("XPath changed from {} to {} relatedFrame: {}", xpath, newXPath,
			        eventable.getRelatedFrame());
			eventToFire =
			        new Eventable(new Identification(Identification.How.xpath, newXPath),
			                eventType);
		}
		return eventToFire;
	}

	private boolean visitAnchorHrefIfPossible(Eventable eventable) {
		Element element = eventable.getElement();
		String href = element.getAttributeOrNull("href");
		if (href == null) {
			LOG.info("Anchor {} has no href and is invisble so it will be ignored", element);
		} else {
			LOG.info("Found an invisible link with href={}", href);
			try {
				URL url = UrlUtils.extractNewUrl(browser.getCurrentUrl(), href);
				browser.goToUrl(url);
				return true;
			} catch (MalformedURLException e) {
				LOG.info("Could not visit invisible illegal URL {}", e.getMessage());
			}
		}
		return false;
	}

	private void crawlThroughActions(StateVertex originalState) {
		String stateName = originalState.getName();
		CandidateCrawlAction action = candidateActionCache.pollActionOrNull(stateName);
		while (action != null) {
			Eventable event = new Eventable(action.getCandidateElement(), action.getEventType());
			handleInputElements(event);
			waitForRefreshTagIfAny(event);

			boolean fired = fireEvent(event);
			if (fired) {
				if (crawlerLeftDomain()) {
					LOG.debug("The browser left the domain");
					goBackOneState();
				} else {
					StateVertex newState =
					        new StateVertex(browser.getCurrentUrl(),
					                session.get().getStateFlowGraph().getNewStateName(),
					                browser.getDom(),
					                stateComparator.getStrippedDom(browser));
					if (domChanged(event, newState)) {
						crawlpath.add(event);
						boolean isNewState =
						        stateMachine.swithToStateAndCheckIfClone(event, newState,
						                browser, session.get());
						if (isNewState) {
							int depth = crawlDepth.incrementAndGet();
							LOG.info("Crawl depth is now {}", depth);
							// TODO scan for work
						} else {
							session.get().addCrawlPath(crawlpath.immutableCopy(false));
						}
						LOG.debug("Dom changed, now crawling the new state.");
					} else {
						LOG.debug("Dom unchanged");
					}
				}
			}
			action = candidateActionCache.pollActionOrNull(stateName);
		}
	}

	private void waitForRefreshTagIfAny(final Eventable eventable) {
		if (eventable.getElement().getTag().equalsIgnoreCase("meta")) {
			Pattern p = Pattern.compile("(\\d+);\\s+URL=(.*)");
			for (Entry<String, String> e : eventable.getElement().getAttributes().entrySet()) {
				Matcher m = p.matcher(e.getValue());
				long waitTime = parseWaitTimeOrReturnDefault(m);
				try {
					Thread.sleep(waitTime);
				} catch (InterruptedException ex) {
					LOG.info("Crawler timed out while waiting for page to reload");
				}
			}
		}
	}

	private boolean crawlerLeftDomain() {
		return !browser.getCurrentUrl().toLowerCase()
		        .contains(url.getHost().toLowerCase());
	}

	private long parseWaitTimeOrReturnDefault(Matcher m) {
		long waitTime = TimeUnit.SECONDS.toMillis(10);
		if (m.find()) {
			LOG.debug("URL: {}", m.group(2));
			try {
				waitTime = Integer.parseInt(m.group(1)) * 1000;
			} catch (NumberFormatException ex) {
				LOG.info("Could parse the amount of time to wait for a META tag refresh. Waiting 10 seconds...");
			}
		}
		return waitTime;
	}

	private boolean domChanged(final Eventable eventable, StateVertex newState) {
		return plugins.runDomChangeNotifierPlugins(stateMachine.getCurrentState(),
		        eventable, newState, browser);
	}

	private void goBackOneState() {
		LOG.debug("Going back one state");
		CrawlPath currentPath = crawlpath.immutableCopy(false);
		crawlpath = null;

		reset();
		follow(currentPath);
	}

	/**
	 * This method calls the index state. It should be called once per crawl in order to setup the
	 * crawl.
	 * 
	 * @return The initial state.
	 */
	public StateVertex crawlIndex() {
		LOG.debug("Setting up vertex of the index page");
		browser.goToUrl(url);
		return new StateVertex(url.toExternalForm(), "index", browser.getDom(),
		        stateComparator.getStrippedDom(browser));

	}
}
