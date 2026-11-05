# Changelog

## [1.4.0](https://github.com/Peczenko/3d-packing-backend/compare/v1.3.0...v1.4.0) (2026-11-05)


### Features

* migrate to Temporal packing ([#74](https://github.com/Peczenko/3d-packing-backend/issues/74)) ([6f8be47](https://github.com/Peczenko/3d-packing-backend/commit/6f8be4774d96f90054c91b5e3a7e3c49324301b4))
* packing job input ([#75](https://github.com/Peczenko/3d-packing-backend/issues/75)) ([99fd61a](https://github.com/Peczenko/3d-packing-backend/commit/99fd61a68a681583f162d2fc3e6ac1d3b78093d3))
* update packer cli to v0.0.3 ([#79](https://github.com/Peczenko/3d-packing-backend/issues/79)) ([be54a38](https://github.com/Peczenko/3d-packing-backend/commit/be54a3806c93284573de2a53db793e2150fb8f2b))


### Chores & Dependencies

* **ci:** bump actions/setup-go from 6 to 7 ([#68](https://github.com/Peczenko/3d-packing-backend/issues/68)) ([74734d5](https://github.com/Peczenko/3d-packing-backend/commit/74734d5e0ac96c858bb10c0824ccf8e1adafb951))
* **ci:** bump actions/setup-java from 5 to 6 ([#73](https://github.com/Peczenko/3d-packing-backend/issues/73)) ([785e636](https://github.com/Peczenko/3d-packing-backend/commit/785e636fb0220bf99d6bfbdb80c38ac969488b7e))
* **ci:** bump actions/upload-artifact from 4 to 7 ([#57](https://github.com/Peczenko/3d-packing-backend/issues/57)) ([6e4f217](https://github.com/Peczenko/3d-packing-backend/commit/6e4f217b5e2178c0fe4f150a44742927511b34c7))
* **ci:** bump azure/cli from 2 to 3 ([#70](https://github.com/Peczenko/3d-packing-backend/issues/70)) ([efe0062](https://github.com/Peczenko/3d-packing-backend/commit/efe006262e43f5c96b34b124e963a5ddb86ba146))
* **ci:** bump azure/login from 2 to 3 ([#60](https://github.com/Peczenko/3d-packing-backend/issues/60)) ([8888f98](https://github.com/Peczenko/3d-packing-backend/commit/8888f985c4e05f65a6fc18ec11126ae911921934))
* **ci:** bump docker/setup-buildx-action from 3 to 4 ([#59](https://github.com/Peczenko/3d-packing-backend/issues/59)) ([2485a67](https://github.com/Peczenko/3d-packing-backend/commit/2485a676cfa331caa382b0d03ff886e63b9fea95))
* **ci:** bump googleapis/release-please-action from 4 to 5 ([#58](https://github.com/Peczenko/3d-packing-backend/issues/58)) ([9ac0fc1](https://github.com/Peczenko/3d-packing-backend/commit/9ac0fc1c79a12c85a0f41e6f06c2369641e6afeb))
* **ci:** bump gradle/actions from 4 to 6 ([#56](https://github.com/Peczenko/3d-packing-backend/issues/56)) ([9960149](https://github.com/Peczenko/3d-packing-backend/commit/99601495b966156110b268af2b4803c10f5ad437))
* **ci:** ignore dependabot updates that break version lockstep ([#65](https://github.com/Peczenko/3d-packing-backend/issues/65)) ([1f284d5](https://github.com/Peczenko/3d-packing-backend/commit/1f284d565eaf6ec8335ab4fba07acef4237bbe62))
* **deps:** bump dev.monosoul.jooq-docker from 8.0.30 to 9.0.1 ([#62](https://github.com/Peczenko/3d-packing-backend/issues/62)) ([1e2688f](https://github.com/Peczenko/3d-packing-backend/commit/1e2688fb2f4e418b336f7662ff67aee1b52adb31))
* **deps:** bump org.postgresql:postgresql from 42.7.11 to 42.7.13 ([#63](https://github.com/Peczenko/3d-packing-backend/issues/63)) ([29e2555](https://github.com/Peczenko/3d-packing-backend/commit/29e25551c4510a4ddd15a81c5e4a0bd9f59965a4))

## [1.3.0](https://github.com/Peczenko/3d-packing-backend/compare/v1.2.1...v1.3.0) (2026-08-19)


### Features

* extend pageable endpoints with collection queries ([#53](https://github.com/Peczenko/3d-packing-backend/issues/53)) ([51e10f3](https://github.com/Peczenko/3d-packing-backend/commit/51e10f3a3d147bcaa57d83dda6c73114df1bcc28))
* user search autocomplete ([#52](https://github.com/Peczenko/3d-packing-backend/issues/52)) ([a3e7201](https://github.com/Peczenko/3d-packing-backend/commit/a3e720182de1159cc22ce7699dd6e06e6d16509a))

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
