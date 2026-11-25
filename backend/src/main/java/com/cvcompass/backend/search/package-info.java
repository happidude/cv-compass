@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "web",
            "candidate::catalog-links",
            "candidate::metrics",
            "candidate::events",
            "catalog::lookup",
            "mapping::results"
        })
package com.cvcompass.backend.search;
