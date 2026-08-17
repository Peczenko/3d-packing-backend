# Changelog

## [1.2.1](https://github.com/Peczenko/3d-packing-backend/compare/v1.2.0...v1.2.1) (2026-08-17)


### Bug Fixes

* align netty to a single version ([e9444e0](https://github.com/Peczenko/3d-packing-backend/commit/e9444e09cecc71fa939e271bd92f54e1fbc2b07a))
* align netty to a single version ([bab221a](https://github.com/Peczenko/3d-packing-backend/commit/bab221a82924dbb02083bfbbd7781043b9640257))

## [1.2.0](https://github.com/Peczenko/3d-packing-backend/compare/v1.1.0...v1.2.0) (2026-08-17)


### Features

* add swagger docs ([#44](https://github.com/Peczenko/3d-packing-backend/issues/44)) ([b587062](https://github.com/Peczenko/3d-packing-backend/commit/b58706218654938ce0a720432041bb0ec6a272de))
* send email about packing job status after the job finished, refactor email infra ([#45](https://github.com/Peczenko/3d-packing-backend/issues/45)) ([519ad1e](https://github.com/Peczenko/3d-packing-backend/commit/519ad1e4960b1f9ea44b06906108190c75a99d6e))


### Refactoring

* remove redundant comments ([#46](https://github.com/Peczenko/3d-packing-backend/issues/46)) ([ac97dbf](https://github.com/Peczenko/3d-packing-backend/commit/ac97dbf1af62ce1fd0bdb4fdc3fbf9393a0153ad))


### CI/CD

* bump actions/checkout from 4 to 7 ([#7](https://github.com/Peczenko/3d-packing-backend/issues/7)) ([831b5ab](https://github.com/Peczenko/3d-packing-backend/commit/831b5abccf592fb7db7d7106751496cde3e5f42b))
* bump actions/setup-java from 4 to 5 ([#5](https://github.com/Peczenko/3d-packing-backend/issues/5)) ([7e3d78c](https://github.com/Peczenko/3d-packing-backend/commit/7e3d78c870e8f13fe3680bdb4128b108906de6cd))
* bump docker/build-push-action from 6 to 7 ([#1](https://github.com/Peczenko/3d-packing-backend/issues/1)) ([8e2d81e](https://github.com/Peczenko/3d-packing-backend/commit/8e2d81e189966951b23e1f8e628c2b61d4658b0e))
* bump docker/login-action from 3 to 4 ([#2](https://github.com/Peczenko/3d-packing-backend/issues/2)) ([1ca7011](https://github.com/Peczenko/3d-packing-backend/commit/1ca70114178f9a470f3d93ad8e681a8c917b8165))
* bump gradle/actions from 4 to 6 ([#8](https://github.com/Peczenko/3d-packing-backend/issues/8)) ([4f2990c](https://github.com/Peczenko/3d-packing-backend/commit/4f2990c93b2b327279ee3e6772ae23ef41a3badb))


### Chores & Dependencies

* bump com.google.firebase:firebase-admin ([#25](https://github.com/Peczenko/3d-packing-backend/issues/25)) ([9e8642f](https://github.com/Peczenko/3d-packing-backend/commit/9e8642fd2b30bc5d69b7c3fbf1e3ba39f3a369ae))
* bump gradle-wrapper from 8.14.5 to 9.7.0 ([#3](https://github.com/Peczenko/3d-packing-backend/issues/3)) ([507b53a](https://github.com/Peczenko/3d-packing-backend/commit/507b53ae7433b1d48513127e812035e40faf9572))
* bump org.gradle.toolchains.foojay-resolver-convention from 0.9.0 to 1.0.0 ([#4](https://github.com/Peczenko/3d-packing-backend/issues/4)) ([c8700ef](https://github.com/Peczenko/3d-packing-backend/commit/c8700ef6d3207b23f4c067168276c280e80b0686))

## [1.1.0](https://github.com/Peczenko/3d-packing-backend/compare/v1.0.0...v1.1.0) (2026-08-16)


### Features

* packing pipeline ([#39](https://github.com/Peczenko/3d-packing-backend/issues/39)) ([fea2483](https://github.com/Peczenko/3d-packing-backend/commit/fea248326f0b4ea6952b1f7d567baf2649ec750b))


### Refactoring

* apply new code style ([#40](https://github.com/Peczenko/3d-packing-backend/issues/40)) ([4adf51f](https://github.com/Peczenko/3d-packing-backend/commit/4adf51f251a7eb4a9c997418871ca9cbb54db333))


### Chores & Dependencies

* release v1.0.0 ([#37](https://github.com/Peczenko/3d-packing-backend/issues/37)) ([009ea11](https://github.com/Peczenko/3d-packing-backend/commit/009ea11f579dd3a1741efa5057262b397d1eed87))

## [1.0.0](https://github.com/Peczenko/3d-packing-backend/compare/v0.0.5...v1.0.0) (2026-07-31)


### ⚠ BREAKING CHANGES

* projects and file management ([#27](https://github.com/Peczenko/3d-packing-backend/issues/27))

### Features

* add email notifications and server error alerting ([#26](https://github.com/Peczenko/3d-packing-backend/issues/26)) ([8a564eb](https://github.com/Peczenko/3d-packing-backend/commit/8a564eb432688dc0e90ed3e13b91b5f7bb4b09dd))
* expose build version on /actuator/info ([#22](https://github.com/Peczenko/3d-packing-backend/issues/22)) ([e2f9e32](https://github.com/Peczenko/3d-packing-backend/commit/e2f9e3208e6913f527847db5de6566952529c032))
* jooq runtime settings ([#33](https://github.com/Peczenko/3d-packing-backend/issues/33)) ([f716af6](https://github.com/Peczenko/3d-packing-backend/commit/f716af6db5bab1650500f53df140e7c465802567))
* projects and file management ([#27](https://github.com/Peczenko/3d-packing-backend/issues/27)) ([c725bd6](https://github.com/Peczenko/3d-packing-backend/commit/c725bd6bd782af9274ee73a3ef556b45c635d65f))


### Bug Fixes

* exact constraint violation mapping ([#29](https://github.com/Peczenko/3d-packing-backend/issues/29)) ([de858de](https://github.com/Peczenko/3d-packing-backend/commit/de858de7b959cb2d34e694bf4719297604fe72d6))
* fix duplicated buildInfo() gradle task ([8cdd232](https://github.com/Peczenko/3d-packing-backend/commit/8cdd232940d94acb74c440ba7e2672f2b123035f))
* secret hygiene and alert leak ([#28](https://github.com/Peczenko/3d-packing-backend/issues/28)) ([1924e00](https://github.com/Peczenko/3d-packing-backend/commit/1924e008fe50a833b597424352266d9893df9c92))


### Refactoring

* paging takes the order separately ([#32](https://github.com/Peczenko/3d-packing-backend/issues/32)) ([d5560e3](https://github.com/Peczenko/3d-packing-backend/commit/d5560e3cc9898c43e2c2fbd610423b423c93970f))


### Build System

* generate jooq from postgres ([#34](https://github.com/Peczenko/3d-packing-backend/issues/34)) ([1e5c8fc](https://github.com/Peczenko/3d-packing-backend/commit/1e5c8fc4fc324620749f26bcd449fb53afcc6240))
* make jooqCodegen up-to-date and cacheable ([#31](https://github.com/Peczenko/3d-packing-backend/issues/31)) ([3dad2e9](https://github.com/Peczenko/3d-packing-backend/commit/3dad2e987dff00a01cd39234182fb605feeb7f2d))


### Chores & Dependencies

* resync master with develop ([2dc8ec3](https://github.com/Peczenko/3d-packing-backend/commit/2dc8ec3a8c0b432f41f324666428838db3a18ada))

## [0.0.5](https://github.com/Peczenko/3d-packing-backend/compare/v0.0.4...v0.0.5) (2026-07-25)


### Bug Fixes

* target release-please at master instead of the default branch ([70a9a51](https://github.com/Peczenko/3d-packing-backend/commit/70a9a5164e0ebd0542fa01f2f3a6f93072ed5638))
* target release-please at master instead of the default branch ([f94c0fd](https://github.com/Peczenko/3d-packing-backend/commit/f94c0fdd31d3ce25c45445bf30bb655d89456010))


### Refactoring

* add lombok and refactor code ([#16](https://github.com/Peczenko/3d-packing-backend/issues/16)) ([cc3c8b2](https://github.com/Peczenko/3d-packing-backend/commit/cc3c8b2e5e22ff535c934fe717f86dc744949c5d))


### Chores & Dependencies

* automate releases with release-please ([#18](https://github.com/Peczenko/3d-packing-backend/issues/18)) ([e70f7f7](https://github.com/Peczenko/3d-packing-backend/commit/e70f7f74ecb9e6cd6dda04deb0a357475665b82b))
* reconcile master's 0.0.4 squash into dev ([1cba4b7](https://github.com/Peczenko/3d-packing-backend/commit/1cba4b758859e4d291939c750a20a85b336e36a6))
