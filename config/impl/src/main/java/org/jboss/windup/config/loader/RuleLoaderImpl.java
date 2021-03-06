package org.jboss.windup.config.loader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.jboss.forge.furnace.proxy.Proxies;
import org.jboss.forge.furnace.services.Imported;
import org.jboss.windup.config.AbstractRuleProvider;
import org.jboss.windup.config.RuleProvider;
import org.jboss.windup.config.metadata.RuleProviderRegistry;
import org.jboss.windup.config.phase.RulePhase;
import org.jboss.windup.util.ServiceLogger;
import org.jboss.windup.util.exception.WindupException;
import org.ocpsoft.rewrite.bind.Evaluation;
import org.ocpsoft.rewrite.config.Condition;
import org.ocpsoft.rewrite.config.ConditionVisit;
import org.ocpsoft.rewrite.config.Configuration;
import org.ocpsoft.rewrite.config.ConfigurationBuilder;
import org.ocpsoft.rewrite.config.Operation;
import org.ocpsoft.rewrite.config.OperationVisit;
import org.ocpsoft.rewrite.config.ParameterizedCallback;
import org.ocpsoft.rewrite.config.ParameterizedConditionVisitor;
import org.ocpsoft.rewrite.config.ParameterizedOperationVisitor;
import org.ocpsoft.rewrite.config.Rule;
import org.ocpsoft.rewrite.config.RuleBuilder;
import org.ocpsoft.rewrite.param.ConfigurableParameter;
import org.ocpsoft.rewrite.param.DefaultParameter;
import org.ocpsoft.rewrite.param.Parameter;
import org.ocpsoft.rewrite.param.ParameterStore;
import org.ocpsoft.rewrite.param.Parameterized;
import org.ocpsoft.rewrite.param.ParameterizedRule;
import org.ocpsoft.rewrite.util.Visitor;

public class RuleLoaderImpl implements RuleLoader
{
    public static Logger LOG = Logger.getLogger(RuleLoaderImpl.class.getName());

    @Inject
    private Imported<RuleProviderLoader> loaders;

    public RuleLoaderImpl()
    {
    }

    @Override
    public RuleProviderRegistry loadConfiguration(RuleLoaderContext ruleLoaderContext)
    {
        return build(ruleLoaderContext);
    }

    /**
     * Prints all of the {@link RulePhase} objects in the order that they should execute. This is primarily for debug purposes and should be called
     * before the entire {@link RuleProvider} list is sorted, as this will allow us to print the {@link RulePhase} list without the risk of
     * user-introduced cycles making the sort impossible.
     */
    private void printRulePhases(List<RuleProvider> allProviders)
    {
        List<RuleProvider> unsortedPhases = new ArrayList<>();
        for (RuleProvider provider : allProviders)
        {
            if (provider instanceof RulePhase)
                unsortedPhases.add(provider);
        }
        List<RuleProvider> sortedPhases = RuleProviderSorter.sort(unsortedPhases);
        StringBuilder rulePhaseSB = new StringBuilder();
        for (RuleProvider phase : sortedPhases)
        {
            Class<?> unproxiedClass = Proxies.unwrap(phase).getClass();
            rulePhaseSB.append("\tPhase: ").append(unproxiedClass.getSimpleName()).append("\n");
        }
        LOG.info("Rule Phases: [\n" + rulePhaseSB.toString() + "]");
    }

    private void checkForDuplicateProviders(List<RuleProvider> providers)
    {
        /*
         * We are using a map so that we can easily pull out the previous value later (in the case of a duplicate)
         */
        Map<RuleProvider, RuleProvider> duplicates = new HashMap<>(providers.size());
        for (RuleProvider provider : providers)
        {
            RuleProvider previousProvider = duplicates.get(provider);
            if (previousProvider != null)
            {
                String typeMessage;
                String currentProviderOrigin = provider.getMetadata().getOrigin();
                String previousProviderOrigin = previousProvider.getMetadata().getOrigin();
                if (previousProvider.getClass().equals(provider.getClass()))
                {
                    typeMessage = " (type: " + previousProviderOrigin + " and " + currentProviderOrigin + ")";
                }
                else
                {
                    typeMessage = " (types: " + Proxies.unwrapProxyClassName(previousProvider.getClass()) + " at " + previousProviderOrigin
                                + " and " + Proxies.unwrapProxyClassName(provider.getClass()) + " at " + currentProviderOrigin + ")";
                }

                throw new WindupException("Found two providers with the same id: " + provider.getMetadata().getID() + typeMessage);
            }
            duplicates.put(provider, provider);
        }
    }

    private List<RuleProvider> getProviders(RuleLoaderContext ruleLoaderContext)
    {
        List<RuleProvider> unsortedProviders = new ArrayList<>();
        for (RuleProviderLoader loader : loaders)
        {
            unsortedProviders.addAll(loader.getProviders(ruleLoaderContext));
        }

        checkForDuplicateProviders(unsortedProviders);

        printRulePhases(unsortedProviders);

        List<RuleProvider> sortedProviders = RuleProviderSorter.sort(unsortedProviders);
        ServiceLogger.logLoadedServices(LOG, RuleProvider.class, sortedProviders);

        return Collections.unmodifiableList(sortedProviders);
    }

    private RuleProviderRegistry build(RuleLoaderContext ruleLoaderContext)
    {
        List<Rule> allRules = new ArrayList<>(2000); // estimate of how many rules we will likely see

        List<RuleProvider> providers = getProviders(ruleLoaderContext);
        RuleProviderRegistry registry = new RuleProviderRegistry();
        registry.setProviders(providers);

        Map<RuleKey, Rule> overrideRules = new HashMap<>();
        for (RuleProvider provider : providers)
        {
            if (!provider.getMetadata().isOverrideProvider())
                continue;

            Configuration cfg = provider.getConfiguration(null);
            List<Rule> rules = cfg.getRules();
            for (Rule rule : rules)
                overrideRules.put(new RuleKey(provider.getMetadata().getID(), rule.getId()), rule);
        }

        for (RuleProvider provider : providers)
        {
            if (ruleLoaderContext.getRuleProviderFilter() != null)
            {
                boolean accepted = ruleLoaderContext.getRuleProviderFilter().accept(provider);
                LOG.info((accepted ? "Accepted" : "Skipped") + ": [" + provider + "] by filter [" + ruleLoaderContext.getRuleProviderFilter() + "]");
                if (!accepted)
                    continue;
            }

            // these are not used directly... they only override others
            if (provider.getMetadata().isOverrideProvider())
                continue;

            Configuration cfg = provider.getConfiguration(ruleLoaderContext);

            // copy it to allow for the option of modification
            List<Rule> rules = new ArrayList<>(cfg.getRules());
            ListIterator<Rule> ruleIterator = rules.listIterator();
            while (ruleIterator.hasNext())
            {
                Rule rule = ruleIterator.next();
                Rule overrideRule = overrideRules.get(new RuleKey(provider.getMetadata().getID(), rule.getId()));
                if (overrideRule != null)
                {
                    LOG.info("Replacing rule " + rule.getId() + " with a user override!");
                    ruleIterator.set(overrideRule);
                }
            }

            registry.setRules(provider, rules);

            int i = 0;
            for (final Rule rule : rules)
            {
                i++;

                AbstractRuleProvider.enhanceRuleMetadata(provider, rule);

                if (rule instanceof RuleBuilder && StringUtils.isBlank(rule.getId()))
                {
                    ((RuleBuilder) rule).withId(generatedRuleID(provider, rule, i));
                }

                allRules.add(rule);

                if (rule instanceof ParameterizedRule)
                {
                    ParameterizedCallback callback = new ParameterizedCallback()
                    {
                        @Override
                        public void call(Parameterized parameterized)
                        {
                            Set<String> names = parameterized.getRequiredParameterNames();
                            ParameterStore store = ((ParameterizedRule) rule).getParameterStore();

                            if (names != null)
                                for (String name : names)
                                {
                                    Parameter<?> parameter = store.get(name, new DefaultParameter(name));
                                    if (parameter instanceof ConfigurableParameter<?>)
                                        ((ConfigurableParameter<?>) parameter).bindsTo(Evaluation.property(name));
                                }

                            parameterized.setParameterStore(store);
                        }
                    };

                    Visitor<Condition> conditionVisitor = new ParameterizedConditionVisitor(callback);
                    new ConditionVisit(rule).accept(conditionVisitor);

                    Visitor<Operation> operationVisitor = new ParameterizedOperationVisitor(callback);
                    new OperationVisit(rule).accept(operationVisitor);
                }
            }
        }

        ConfigurationBuilder result = ConfigurationBuilder.begin();
        for (Rule rule : allRules)
        {
            result.addRule(rule);
        }

        registry.setConfiguration(result);
        return registry;
    }

    private String generatedRuleID(RuleProvider provider, Rule rule, int idx)
    {
        String provID = provider.getMetadata().getID();
        return provID + "_" + idx;
    }
}
