/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import tech.beshu.ror.acl.blocks.rules.AsyncRule;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authentication;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authorization;
import tech.beshu.ror.acl.definitions.users.User;
import tech.beshu.ror.acl.definitions.users.UserFactory;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.definitions.UserSettings;
import tech.beshu.ror.settings.rules.GroupsRuleSettings;
import tech.beshu.ror.utils.FuturesSequencer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A GroupsSyncRule checks if a request containing Basic Authentication credentials
 * matches a user in one of the specified groups.
 *
 * @author Christian Henke (maitai@users.noreply.github.com)
 */
public class GroupsAsyncRule extends AsyncRule implements Authorization, Authentication {

  public static final String CURRENT_GROUP_HEADER = "x-ror-current-group";
  private static final boolean ROR_KIBANA_METADATA_ENABLED =
    !"false".equalsIgnoreCase(System.getProperty("com.readonlyrest.kibana.metadata"));
  private static final String AVAILABLE_GROUPS_HEADER = "x-ror-available-groups";
  private final GroupsRuleSettings settings;
  private final Map<String, User> users;


  public GroupsAsyncRule(GroupsRuleSettings s, UserFactory userFactory) {
    this.settings = s;
    this.users = settings.getUsersSettings().stream()
      .map(uSettings -> userFactory.getUser(uSettings))
      .collect(Collectors.toMap(User::getUsername, Function.identity()));
  }

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {

    // All configured groups in a block's group rule, contextualized
    Set<String> resolvedGroups = settings.getGroups().stream()
      .map(g -> g.getValue(rc))
      .filter(Optional::isPresent)
      .map(Optional::get)
      .collect(Collectors.toSet());


    List<UserSettings> userSettingsToCheck = settings.getUsersSettings();

    // Limit the userSettings to examine to the ones which include current group (is specified)
    String preferredGroup = rc.getHeaders().get(CURRENT_GROUP_HEADER);
    if (!Strings.isNullOrEmpty(preferredGroup)) {
      if (!resolvedGroups.contains(preferredGroup)) {
        return CompletableFuture.completedFuture(NO_MATCH);
      }
      userSettingsToCheck = settings.getUsersSettings().stream()
        .filter(us -> us.getGroups().contains(preferredGroup))
        .collect(Collectors.toList());
    }

    // Exclude userSettings that don't contain groups in this groupRule
    userSettingsToCheck = userSettingsToCheck.stream()
      .filter(us -> !Sets.intersection(us.getGroups(), resolvedGroups).isEmpty())
      .collect(Collectors.toList());

    if (userSettingsToCheck.isEmpty()) {
      return CompletableFuture.completedFuture(NO_MATCH);
    }

    // Check remaining userSettings for matching auth
    return FuturesSequencer.runInSeqUntilConditionIsUndone(

      // Iterator of users which match at least one group in this block's group rule
      userSettingsToCheck.iterator(),

      // Asynchronously map for each userSetting, return MATCH when we authenticated the first user
      uSettings -> {
        return users.get(uSettings.getUsername()).getAuthKeyRule().match(rc)
          .exceptionally(e -> {
            e.printStackTrace();
            return NO_MATCH;
          });
      },

      // Boolean decision (true = break loop)
      (uSettings, ruleExit) -> {
        if (!ruleExit.isMatch()) {
          return false;
        }

        // A user has matched authentication with provided credentials, writing resp headers
        if (ROR_KIBANA_METADATA_ENABLED) {

          // ############# CURRENT GROUP HEADER
          if (!Strings.isNullOrEmpty(preferredGroup)) {
            // Mirror preferred group header from request to response.
            rc.setResponseHeader(CURRENT_GROUP_HEADER, preferredGroup);
          }
          else {
            // If no preferred group has been selected, just throw in the first of the list
            Iterator<String> i = uSettings.getGroups().iterator();
            if (i.hasNext()) {
              rc.setResponseHeader(CURRENT_GROUP_HEADER, i.next());
            }
          }

          // ############# AVAILABLE GROUPS HEADER
          // #TODO add groups (with indices and kibana access and kibana index) to RC.
          rc.setResponseHeader(AVAILABLE_GROUPS_HEADER, Joiner.on(",").join(uSettings.getGroups()));

        }

        return true;
      },

      // If never true..
      nothing -> NO_MATCH
    ).exceptionally(e -> {
      e.printStackTrace();
      throw new CompletionException(e);
    });

  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}
