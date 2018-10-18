# Settings Repository Plus
This plugin is based on the original <a href="https://github.com/JetBrains/intellij-community/tree/master/plugins/settings-repository">Settings Repository</a> plugin.
To enhance the original plugin.

# User Guide

1. Disable the original `Settings Repository` plugin in Settings | Plugins
2. Install from `Settings -> Plugins -> Browse repositories` -> type in `Settings Repository Plus`
3. Navigate to `File -> Settings Repository…` to configure
4. Inpute the **Upstream URL** and **Remote Branch**
5. Click the **Merge** button to sync your IDE settings

# Configuration
## Synchronization
There are two types of **Upstream URL** are supported:

- Remote Repository: eg. [GitHub](https://www.github.com) could be used to store settings.
- Local Repository: File URL is supported, you will be prompted to init repository if specified path is not exists or repository is not created.

Also you can do sync using "VCS -> Sync Settings". 
Synchronization is performed automatically after successful completion of "Update Project" or "Push" actions.

## Authentication
Below are some instructions if you use Remote Repository like GitHub as your **Upstream URL**:

On first sync you will be prompted to specify username/password. In case of GitHub strongly recommended to use an [access token](https://help.github.com/articles/creating-an-access-token-for-command-line-use) (leave password empty if you use token instead of username). Bitbucket [doesn't support tokens](https://bitbucket.org/site/master/issue/7735).

If you still want to use username/password instead of access token or your Git hosting provider doesn't support it, recommended to configure [git credentials helper](https://help.github.com/articles/caching-your-github-password-in-git).

OS X Keychain is supported. It means that your credentials could be shared between all IntelliJ Platform based products (you will be prompted to grant access if origin application differs from requestor application (credentials were saved in IntelliJ IDEA, but requested from WebStorm).


# Suggestion
Create a remote repository named `IntelliJSettings` and sync to different branches:

- idea-linux
- idea-windows
- pycharm-linux
- pycharm-windows
- ...


# Screenshots
![settings repository plus_001](https://user-images.githubusercontent.com/15963141/47128528-24f10b80-d2c4-11e8-984e-e9840561cf1b.png)


# Changelog
## v0.1.0 (2018.08.15)

*New Features:*

- Supports specifying custom remote branch in repository
