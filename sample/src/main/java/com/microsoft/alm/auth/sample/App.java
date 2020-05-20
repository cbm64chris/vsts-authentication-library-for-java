// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.alm.auth.sample;

import com.microsoft.alm.auth.PromptBehavior;
import com.microsoft.alm.auth.oauth.DeviceFlowResponse;
import com.microsoft.alm.auth.oauth.OAuth2Authenticator;
import com.microsoft.alm.auth.pat.VstsPatAuthenticator;
import com.microsoft.alm.common.helpers.Action;
import com.microsoft.alm.provider.JaxrsClientProvider;
import com.microsoft.alm.provider.Options;
import com.microsoft.alm.provider.UserPasswordCredentialProvider;
import com.microsoft.alm.common.secret.Credential;
import com.microsoft.alm.common.secret.Secret;
import com.microsoft.alm.common.secret.Token;
import com.microsoft.alm.common.secret.TokenPair;
import com.microsoft.alm.common.secret.VsoTokenScope;
import com.microsoft.alm.sourcecontrol.webapi.GitHttpClient;
import com.microsoft.alm.sourcecontrol.webapi.model.GitRepository;
import com.microsoft.alm.common.storage.InsecureInMemoryStore;
import com.microsoft.alm.common.storage.SecretStore;
import com.microsoft.alm.storage.StorageProvider;
import com.microsoft.alm.storage.StorageProvider.SecureOption;
import com.microsoft.visualstudio.services.account.Account;
import com.microsoft.visualstudio.services.account.AccountHttpClient;

import javax.ws.rs.client.Client;
import java.net.URI;
import java.util.List;
import java.util.UUID;

public class App {
    //azure connection settings, please supply your own
    private final static String CLIENT_ID = "YOUR_CLIENT_GUID"; //$NON-NLS-1$
    private final static String REDIRECT_URL = "YOUR_REDIRECT_URL"; //$NON-NLS-1$

    public static void main(String args[]) {
        // Create the storage for OAuth token and token, if you already have an OAuth store that contains
        // valid OAuth AccessTokens, this is the place to reuse them
        final SecretStore<TokenPair> accessTokenStore = new InsecureInMemoryStore<TokenPair>();
        final SecretStore<Token> tokenStore = StorageProvider.getTokenStorage(false, SecureOption.PREFER);

        final Action<DeviceFlowResponse> deviceFlowResponseAction = new Action<DeviceFlowResponse>() {
            @Override
            public void call(DeviceFlowResponse deviceFlowResponse) {
                System.out.println("Go to the following url: ");
                System.out.println(deviceFlowResponse.getVerificationUri());
                System.out.println("and enter this code: ");
                System.out.println(deviceFlowResponse.getUserCode());
            }
        };

        final Secret.IUriNameConversion uriToKey = new Secret.PrefixedUriNameConversion("alm-auth:");

        //First create the authenticator
        final OAuth2Authenticator oAuth2Authenticator = OAuth2Authenticator.getAuthenticator(CLIENT_ID, REDIRECT_URL,
                accessTokenStore, deviceFlowResponseAction);
        oAuth2Authenticator.setUriToKeyConversion(uriToKey);

        final VstsPatAuthenticator patAuthenticator = new VstsPatAuthenticator(oAuth2Authenticator, tokenStore);

        //Create a jaxrs client provider with this authenticator
        final JaxrsClientProvider clientProvider = new JaxrsClientProvider(patAuthenticator);

        //Set up options to create PAT in case there is nothing stored
        final Options options = Options.getDefaultOptions();
        options.patGenerationOptions.displayName = "E2E Manual Testing PAT";
        options.patGenerationOptions.tokenScope = VsoTokenScope.AllScopes; // leave it to ALL if we want to manage wit

        // Get a client with global privilege to look up all accounts
        final Client client = clientProvider.getClient(PromptBehavior.AUTO, options);

        // Get list of accounts
        final AccountHttpClient accountHttpClient
                = new AccountHttpClient(client, URI.create("https://app.vssps.visualstudio.com"));

        UUID myId = accountHttpClient.getMyProfile().getId();
        List<Account> accounts = accountHttpClient.getAccounts(myId);

        // now picked out one account from the list, and assume we cloned it, we should transfer the global PAT
        // to this particular account we cloned
        URI targetAcct = URI.create("https://" + accounts.get(0).getAccountName() + ".visualstudio.com");

        // save this pat for the cloned account so we have credential for it
        patAuthenticator.assignGlobalPatTo(targetAcct);

        // Now after awhile we come back to do more with the git url, such as pull request.
        // Should not prompt for credentials since we have transferred the global PAT to this account before
        final Client specificClient = clientProvider.getClientFor(targetAcct);

        GitHttpClient gitHttpClient = new GitHttpClient(specificClient, targetAcct);
        List<GitRepository> repos = gitHttpClient.getRepositories();
        System.out.println(repos.get(0).getName());

        // You can just create a new PAT authenticator as long as you use the same storage, you should get
        // same PAT without being prompted
        // The accessToken store is not important since accessToken expires in an hour anyway
        final VstsPatAuthenticator newPatAuthenticator = new VstsPatAuthenticator(CLIENT_ID, REDIRECT_URL,
                new InsecureInMemoryStore<TokenPair>(),  // new AccessToken store
                tokenStore); // same persisted token store
        final UserPasswordCredentialProvider passwordCredentialProvider
                = new UserPasswordCredentialProvider(newPatAuthenticator);

        Credential credential = passwordCredentialProvider.getCredentialFor(targetAcct);

        System.out.println(credential.Username + ":" + credential.Password);

        // switch to another user account
        patAuthenticator.signOut();

        // Now this should prompt again
        // if no option passed in, we will just generate a PAT with default name
        final Client anotherClient = clientProvider.getClient();
    }
}
